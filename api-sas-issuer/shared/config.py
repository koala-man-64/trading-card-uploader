from __future__ import annotations

import os
from dataclasses import dataclass

DEFAULT_AZURE_STORAGE_API_VERSION = "2021-08-06"


def _csv(value: str | None) -> tuple[str, ...]:
    if not value:
        return ()
    return tuple(part.strip() for part in value.split(",") if part.strip())


def _int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return int(raw)


@dataclass(frozen=True)
class Settings:
    environment: str
    entra_tenant_id: str
    api_client_id: str
    api_app_id_uri: str
    allowed_audiences: tuple[str, ...]
    required_scope: str
    allowed_android_client_ids: tuple[str, ...]
    upload_storage_account_url: str
    upload_container_name: str
    max_upload_bytes: int
    sas_ttl_minutes: int
    allowed_content_types: tuple[str, ...]
    hash_salt: str
    sas_signer_mode: str
    storage_connection_string: str | None
    managed_identity_client_id: str | None
    storage_api_version: str

    @classmethod
    def from_env(cls) -> Settings:
        api_client_id = os.getenv("API_CLIENT_ID", "").strip()
        api_app_id_uri = os.getenv("API_APP_ID_URI", f"api://{api_client_id}").strip()
        audiences = _csv(os.getenv("ALLOWED_AUDIENCES")) or tuple(
            audience for audience in (api_app_id_uri, api_client_id) if audience
        )

        settings = cls(
            environment=os.getenv("ENVIRONMENT", "dev").strip().lower(),
            entra_tenant_id=os.getenv("ENTRA_TENANT_ID", "").strip(),
            api_client_id=api_client_id,
            api_app_id_uri=api_app_id_uri,
            allowed_audiences=audiences,
            required_scope=os.getenv("REQUIRED_SCOPE", "upload.write").strip(),
            allowed_android_client_ids=_csv(os.getenv("ALLOWED_ANDROID_CLIENT_IDS")),
            upload_storage_account_url=os.getenv("UPLOAD_STORAGE_ACCOUNT_URL", "").strip(),
            upload_container_name=os.getenv("UPLOAD_CONTAINER_NAME", "card-uploads").strip(),
            max_upload_bytes=_int_env("MAX_UPLOAD_BYTES", 10 * 1024 * 1024),
            sas_ttl_minutes=_int_env("SAS_TTL_MINUTES", 5),
            allowed_content_types=_csv(os.getenv("ALLOWED_CONTENT_TYPES"))
            or ("image/jpeg", "image/heic"),
            hash_salt=os.getenv("HASH_SALT", "").strip(),
            sas_signer_mode=os.getenv("SAS_SIGNER_MODE", "managed_identity").strip(),
            storage_connection_string=os.getenv("AZURE_STORAGE_CONNECTION_STRING"),
            managed_identity_client_id=os.getenv("MANAGED_IDENTITY_CLIENT_ID"),
            storage_api_version=os.getenv(
                "AZURE_STORAGE_API_VERSION",
                DEFAULT_AZURE_STORAGE_API_VERSION,
            ).strip(),
        )
        settings.validate()
        return settings

    def validate(self) -> None:
        missing = []
        for name, value in (
            ("ENTRA_TENANT_ID", self.entra_tenant_id),
            ("API_CLIENT_ID", self.api_client_id),
            ("UPLOAD_STORAGE_ACCOUNT_URL", self.upload_storage_account_url),
            ("UPLOAD_CONTAINER_NAME", self.upload_container_name),
            ("HASH_SALT", self.hash_salt),
        ):
            if not value:
                missing.append(name)
        if missing:
            raise ValueError(f"Missing required settings: {', '.join(missing)}")
        if self.max_upload_bytes <= 0:
            raise ValueError("MAX_UPLOAD_BYTES must be positive")
        if self.sas_ttl_minutes <= 0 or self.sas_ttl_minutes > 15:
            raise ValueError("SAS_TTL_MINUTES must be between 1 and 15")
        if self.sas_signer_mode not in {"managed_identity", "connection_string"}:
            raise ValueError("SAS_SIGNER_MODE must be managed_identity or connection_string")
        if self.sas_signer_mode == "connection_string" and not self.storage_connection_string:
            raise ValueError("AZURE_STORAGE_CONNECTION_STRING is required for connection_string mode")
        if not self.storage_api_version:
            raise ValueError("AZURE_STORAGE_API_VERSION must not be blank")
