from __future__ import annotations

import base64
from dataclasses import replace
from datetime import UTC, datetime
from typing import Any, cast
from urllib.parse import parse_qs, urlparse

import pytest
from azure.core.exceptions import ResourceNotFoundError

from shared import gallery as gallery_module
from shared.config import DEFAULT_AZURE_STORAGE_API_VERSION, Settings
from shared.gallery import (
    delete_raw_source_group,
    list_raw_images,
    require_gallery_admin,
    require_raw_image_blob_name,
)
from shared.models import Claims, Problem
from shared.sas import ConnectionStringSasSigner


class _Blob:
    def __init__(self, name: str, size: int = 0) -> None:
        self.name = name
        self.size = size
        self.last_modified = datetime(2026, 6, 28, tzinfo=UTC)


class _Container:
    def __init__(self, names: list[str]) -> None:
        self._blobs = [_Blob(name, idx + 1) for idx, name in enumerate(names)]

    def list_blobs(self, name_starts_with: str | None = None):
        if name_starts_with is None:
            return list(self._blobs)
        return [blob for blob in self._blobs if blob.name.startswith(name_starts_with)]


class _DeleteBlobClient:
    def __init__(self, container: _DeleteContainer, name: str) -> None:
        self._container = container
        self._name = name

    def delete_blob(self) -> None:
        if self._name not in self._container.names:
            raise ResourceNotFoundError("blob not found")
        self._container.names.remove(self._name)
        self._container.deleted.append(self._name)


class _DeleteContainer:
    def __init__(self, names: list[str]) -> None:
        self.names = set(names)
        self.deleted: list[str] = []

    def get_blob_client(self, blob_name: str) -> _DeleteBlobClient:
        return _DeleteBlobClient(self, blob_name)


def settings() -> Settings:
    return Settings(
        environment="test",
        entra_tenant_id="tenant",
        api_client_id="api-client",
        api_app_id_uri="api://api-client",
        allowed_audiences=("api://api-client", "api-client"),
        required_scope="upload.write",
        allowed_android_client_ids=(),
        upload_storage_account_url="https://upload.blob.core.windows.net",
        upload_container_name="card-uploads",
        max_upload_bytes=10,
        sas_ttl_minutes=5,
        allowed_content_types=("image/jpeg", "image/heic"),
        hash_salt="salt",
        sas_signer_mode="connection_string",
        storage_connection_string=(
            "DefaultEndpointsProtocol=https;"
            "AccountName=upload;"
            f"AccountKey={base64.b64encode(b'0' * 64).decode()};"
            "EndpointSuffix=core.windows.net"
        ),
        managed_identity_client_id=None,
        storage_api_version=DEFAULT_AZURE_STORAGE_API_VERSION,
        gallery_manage_scope="gallery.manage",
        admin_allowed_object_ids=("admin-user",),
        admin_allowed_roles=("Gallery.Admin",),
    )


def claims(
    *,
    scopes: frozenset[str],
    oid: str = "admin-user",
    roles: frozenset[str] = frozenset(),
) -> Claims:
    return Claims(
        tenant_id="tenant",
        object_id=oid,
        audience="api://api-client",
        authorized_party="android-client",
        scopes=scopes,
        roles=roles,
    )


def test_require_gallery_admin_rejects_missing_scope() -> None:
    with pytest.raises(Problem) as exc:
        require_gallery_admin(settings(), claims(scopes=frozenset({"upload.write"})))

    assert exc.value.code == "missing_scope"


def test_require_gallery_admin_accepts_allowed_object_id() -> None:
    require_gallery_admin(settings(), claims(scopes=frozenset({"gallery.manage"})))


def test_require_gallery_admin_accepts_allowed_role() -> None:
    require_gallery_admin(
        settings(),
        claims(
            scopes=frozenset({"gallery.manage"}),
            oid="role-user",
            roles=frozenset({"Gallery.Admin"}),
        ),
    )


def test_require_raw_image_blob_name_rejects_non_raw_targets() -> None:
    with pytest.raises(Problem) as exc:
        require_raw_image_blob_name("manifests/internal.json")

    assert exc.value.code == "invalid_raw_blob_name"


def test_raw_gallery_list_paginates_and_filters_images() -> None:
    payload = list_raw_images(
        settings(),
        cast(
            Any,
            _Container(
                [
                    "raw/a.jpg",
                    "raw/b.txt",
                    "raw/c.heic",
                    "manifests/ignored.json",
                ]
            ),
        ),
        limit=1,
        cursor=None,
    )

    assert payload["nextCursor"] == "1"
    assert payload["items"][0]["name"] == "raw/a.jpg"
    assert "sig=" in payload["items"][0]["previewUrl"]


def test_gallery_preview_sas_is_read_only() -> None:
    url = ConnectionStringSasSigner(settings()).sign_read(
        "raw/a.jpg",
        datetime(2026, 6, 28, 18, 0, tzinfo=UTC),
    )

    permissions = parse_qs(urlparse(url).query)["sp"][0]
    assert "r" in permissions
    assert "c" not in permissions
    assert "w" not in permissions
    assert "d" not in permissions


def test_delete_raw_source_group_skips_scanner_when_not_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    def unexpected_scanner_call(*args: object, **kwargs: object) -> dict[str, Any]:
        raise AssertionError("scanner should not be called")

    monkeypatch.setattr(gallery_module, "scanner_json", unexpected_scanner_call)
    container = _DeleteContainer(["raw/a.jpg"])

    payload = delete_raw_source_group(
        settings(),
        "Bearer token",
        cast(Any, container),
        "raw/a.jpg",
    )

    assert payload == {
        "sourceBlobName": "raw/a.jpg",
        "rawDeleted": True,
        "scanner": {"skipped": True, "reason": "scanner_not_configured"},
    }
    assert container.deleted == ["raw/a.jpg"]


def test_delete_raw_source_group_cascades_when_scanner_is_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[dict[str, Any]] = []

    def scanner_json(
        _settings: Settings,
        authorization: str,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        calls.append(
            {
                "authorization": authorization,
                "method": method,
                "path": path,
                "payload": payload,
            }
        )
        return {"deleted": 2}

    monkeypatch.setattr(gallery_module, "scanner_json", scanner_json)
    container = _DeleteContainer(["raw/a.jpg"])
    configured = replace(settings(), scanner_admin_base_url="http://scanner.test")

    payload = delete_raw_source_group(
        configured,
        "Bearer token",
        cast(Any, container),
        "raw/a.jpg",
    )

    assert payload == {
        "sourceBlobName": "raw/a.jpg",
        "rawDeleted": True,
        "scanner": {"deleted": 2},
    }
    assert calls == [
        {
            "authorization": "Bearer token",
            "method": "POST",
            "path": "/api/v1/admin/gallery/actions/delete-by-source",
            "payload": {"sourceBlobName": "raw/a.jpg"},
        }
    ]
    assert container.deleted == ["raw/a.jpg"]
