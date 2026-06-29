from __future__ import annotations

import base64
from datetime import UTC, datetime
from typing import Any, cast
from urllib.parse import parse_qs, urlparse

import pytest

from shared.config import DEFAULT_AZURE_STORAGE_API_VERSION, Settings
from shared.gallery import list_raw_images, require_gallery_admin, require_raw_image_blob_name
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


def claims(*, scopes: frozenset[str], oid: str = "admin-user") -> Claims:
    return Claims(
        tenant_id="tenant",
        object_id=oid,
        audience="api://api-client",
        authorized_party="android-client",
        scopes=scopes,
    )


def test_require_gallery_admin_rejects_missing_scope() -> None:
    with pytest.raises(Problem) as exc:
        require_gallery_admin(settings(), claims(scopes=frozenset({"upload.write"})))

    assert exc.value.code == "missing_scope"


def test_require_gallery_admin_accepts_allowed_object_id() -> None:
    require_gallery_admin(settings(), claims(scopes=frozenset({"gallery.manage"})))


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
