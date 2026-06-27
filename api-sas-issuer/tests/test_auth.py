from __future__ import annotations

import pytest

from shared.auth import JwtValidator
from shared.config import Settings
from shared.models import Problem


def settings() -> Settings:
    return Settings(
        environment="test",
        entra_tenant_id="tenant",
        api_client_id="api-client",
        api_app_id_uri="api://api-client",
        allowed_audiences=("api://api-client", "api-client"),
        required_scope="upload.write",
        allowed_android_client_ids=("android-client",),
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


def test_claims_require_tenant_scope_and_allowed_client() -> None:
    validator = JwtValidator(settings())
    claims = validator._claims_from_decoded(
        {
            "tid": "tenant",
            "oid": "user",
            "aud": "api://api-client",
            "azp": "android-client",
            "scp": "profile upload.write",
        }
    )

    claims.require_scope("upload.write")
    assert claims.object_id == "user"


def test_missing_scope_is_forbidden() -> None:
    validator = JwtValidator(settings())
    claims = validator._claims_from_decoded(
        {
            "tid": "tenant",
            "oid": "user",
            "aud": "api://api-client",
            "azp": "android-client",
            "scp": "profile",
        }
    )

    with pytest.raises(Problem) as exc:
        claims.require_scope("upload.write")

    assert exc.value.status_code == 403


def test_wrong_tenant_is_rejected() -> None:
    validator = JwtValidator(settings())

    with pytest.raises(Problem) as exc:
        validator._claims_from_decoded(
            {
                "tid": "other",
                "oid": "user",
                "aud": "api://api-client",
                "azp": "android-client",
                "scp": "upload.write",
            }
        )

    assert exc.value.code == "wrong_tenant"
