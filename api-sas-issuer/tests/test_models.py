from __future__ import annotations

from datetime import UTC, datetime

import pytest

from shared.config import Settings
from shared.models import AZURE_STORAGE_API_VERSION, Problem, UploadSasRequest, UploadSasResponse


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


def test_accepts_valid_request() -> None:
    request = UploadSasRequest.from_json(
        {
            "clientUploadId": "11111111-1111-1111-1111-111111111111",
            "contentType": "image/jpeg",
            "contentLengthBytes": 9,
            "sha256Hex": "a" * 64,
        },
        settings(),
    )

    assert request.file_extension == "jpg"
    assert request.fingerprint == f"image/jpeg:9:{'a' * 64}"


def test_accepts_heic_request() -> None:
    request = UploadSasRequest.from_json(
        {
            "clientUploadId": "11111111-1111-1111-1111-111111111111",
            "contentType": "image/heic",
            "contentLengthBytes": 9,
        },
        settings(),
    )

    assert request.file_extension == "heic"
    assert request.fingerprint == "image/heic:9:no-checksum"


def test_upload_response_includes_required_blob_headers() -> None:
    response = UploadSasResponse(
        upload_id="11111111-1111-1111-1111-111111111111",
        blob_name="raw/tenant/user/20260628/upload.jpg",
        upload_url="https://upload.blob.core.windows.net/card-uploads/raw/blob.jpg?sig=test",
        expires_at_utc=datetime(2026, 6, 28, 18, 30, tzinfo=UTC),
        max_content_length_bytes=10,
    )

    assert response.to_json()["requiredHeaders"] == {
        "x-ms-blob-type": "BlockBlob",
        "x-ms-version": AZURE_STORAGE_API_VERSION,
        "Content-Type": "image/jpeg",
    }


@pytest.mark.parametrize(
    ("payload", "code"),
    [
        ({}, "invalid_client_upload_id"),
        (
            {
                "clientUploadId": "11111111-1111-1111-1111-111111111111",
                "contentType": "image/png",
                "contentLengthBytes": 9,
            },
            "unsupported_content_type",
        ),
        (
            {
                "clientUploadId": "11111111-1111-1111-1111-111111111111",
                "contentType": "image/jpeg",
                "contentLengthBytes": 11,
            },
            "content_too_large",
        ),
        (
            {
                "clientUploadId": "11111111-1111-1111-1111-111111111111",
                "contentType": "image/jpeg",
                "contentLengthBytes": 9,
                "sha256Hex": "not-hex",
            },
            "invalid_sha256",
        ),
    ],
)
def test_rejects_invalid_request(payload: dict, code: str) -> None:
    with pytest.raises(Problem) as exc:
        UploadSasRequest.from_json(payload, settings())

    assert exc.value.code == code
