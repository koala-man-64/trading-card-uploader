from __future__ import annotations

import base64
from datetime import UTC, datetime
from urllib.parse import parse_qs, urlparse

import pytest

from shared.config import Settings
from shared.models import Claims, Problem, UploadSasRequest
from shared.sas import (
    ConnectionStringSasSigner,
    MemoryIdempotencyStore,
    SasIssuer,
    StaticSasSigner,
    build_blob_name,
)


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
        sas_signer_mode="managed_identity",
        storage_connection_string=None,
        managed_identity_client_id=None,
    )


def claims() -> Claims:
    return Claims(
        tenant_id="tenant",
        object_id="user",
        audience="api://api-client",
        authorized_party="android-client",
        scopes=frozenset({"upload.write"}),
    )


def request(size: int = 9) -> UploadSasRequest:
    return UploadSasRequest(
        client_upload_id="11111111-1111-1111-1111-111111111111",
        content_type="image/jpeg",
        content_length_bytes=size,
        sha256_hex="a" * 64,
    )


def test_blob_name_is_server_generated_and_partitioned() -> None:
    blob_name = build_blob_name(
        settings(),
        claims(),
        request(),
        datetime(2026, 6, 27, tzinfo=UTC),
    )

    assert blob_name.startswith("raw/")
    assert blob_name.endswith(".jpg")
    assert "20260627" in blob_name
    assert "11111111" not in blob_name


def test_issuer_reuses_same_idempotency_key_for_same_payload() -> None:
    issuer = SasIssuer(settings(), MemoryIdempotencyStore(), StaticSasSigner("https://example/{blobName}?sig=test"))

    first = issuer.issue(request(), claims(), datetime(2026, 6, 27, tzinfo=UTC))
    second = issuer.issue(request(), claims(), datetime(2026, 6, 27, tzinfo=UTC))

    assert first.upload_id == second.upload_id
    assert first.blob_name == second.blob_name


def test_issuer_rejects_idempotency_conflict() -> None:
    issuer = SasIssuer(settings(), MemoryIdempotencyStore(), StaticSasSigner("https://example/{blobName}?sig=test"))
    issuer.issue(request(size=9), claims(), datetime(2026, 6, 27, tzinfo=UTC))

    with pytest.raises(Problem) as exc:
        issuer.issue(request(size=8), claims(), datetime(2026, 6, 27, tzinfo=UTC))

    assert exc.value.status_code == 409


def test_generated_sas_grants_create_write_only() -> None:
    account_key = base64.b64encode(b"0" * 64).decode()
    local_settings = Settings(
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
            f"AccountKey={account_key};"
            "EndpointSuffix=core.windows.net"
        ),
        managed_identity_client_id=None,
    )

    url = ConnectionStringSasSigner(local_settings).sign(
        "raw/tenant/user/20260627/upload.jpg",
        datetime(2026, 6, 27, 18, 30, tzinfo=UTC),
    )

    permissions = parse_qs(urlparse(url).query)["sp"][0]
    assert "c" in permissions
    assert "w" in permissions
    assert "r" not in permissions
    assert "l" not in permissions
    assert "d" not in permissions
