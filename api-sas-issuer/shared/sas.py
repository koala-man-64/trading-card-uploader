from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Protocol
from urllib.parse import urlparse

from azure.core.exceptions import ResourceExistsError, ResourceNotFoundError
from azure.identity import DefaultAzureCredential
from azure.storage.blob import (
    BlobSasPermissions,
    BlobServiceClient,
    ContainerClient,
    ContentSettings,
    generate_blob_sas,
)

from .config import Settings
from .models import Claims, Problem, UploadSasRequest, UploadSasResponse


@dataclass(frozen=True)
class UploadManifest:
    upload_id: str
    blob_name: str
    fingerprint: str
    content_type: str
    content_length_bytes: int


class IdempotencyStore(Protocol):
    def reserve(self, claims: Claims, request: UploadSasRequest, manifest: UploadManifest) -> UploadManifest:
        ...


class SasSigner(Protocol):
    def sign(self, blob_name: str, expires_at: datetime) -> str:
        ...


def _hash(value: str, salt: str) -> str:
    return hashlib.sha256(f"{salt}:{value}".encode()).hexdigest()[:24]


def build_upload_id(claims: Claims, request: UploadSasRequest) -> str:
    return str(
        uuid.uuid5(
            uuid.NAMESPACE_URL,
            f"trading-card-uploader:{claims.tenant_id}:{claims.object_id}:{request.client_upload_id}",
        )
    )


def build_blob_name(settings: Settings, claims: Claims, request: UploadSasRequest, now: datetime) -> str:
    tenant_hash = _hash(claims.tenant_id, settings.hash_salt)
    user_hash = _hash(claims.object_id, settings.hash_salt)
    upload_id = build_upload_id(claims, request)
    date_part = now.astimezone(UTC).strftime("%Y%m%d")
    return f"raw/{tenant_hash}/{user_hash}/{date_part}/{upload_id}.{request.file_extension}"


def _manifest_name(claims: Claims, request: UploadSasRequest, settings: Settings) -> str:
    tenant_hash = _hash(claims.tenant_id, settings.hash_salt)
    user_hash = _hash(claims.object_id, settings.hash_salt)
    return f"manifests/{tenant_hash}/{user_hash}/{request.client_upload_id}.json"


class MemoryIdempotencyStore:
    def __init__(self) -> None:
        self._items: dict[str, UploadManifest] = {}

    def reserve(self, claims: Claims, request: UploadSasRequest, manifest: UploadManifest) -> UploadManifest:
        key = f"{claims.tenant_id}:{claims.object_id}:{request.client_upload_id}"
        existing = self._items.get(key)
        if existing:
            if existing.fingerprint != manifest.fingerprint:
                raise Problem(409, "idempotency_conflict", "clientUploadId was already used")
            return existing
        self._items[key] = manifest
        return manifest


class BlobIdempotencyStore:
    def __init__(self, container_client: ContainerClient, settings: Settings) -> None:
        self._container = container_client
        self._settings = settings

    def reserve(self, claims: Claims, request: UploadSasRequest, manifest: UploadManifest) -> UploadManifest:
        blob = self._container.get_blob_client(_manifest_name(claims, request, self._settings))
        body = json.dumps(manifest.__dict__, sort_keys=True).encode("utf-8")
        try:
            blob.upload_blob(
                body,
                overwrite=False,
                content_settings=ContentSettings(content_type="application/json"),
            )
            return manifest
        except ResourceExistsError:
            existing = json.loads(blob.download_blob().readall())
            existing_manifest = UploadManifest(**existing)
            if existing_manifest.fingerprint != manifest.fingerprint:
                raise Problem(409, "idempotency_conflict", "clientUploadId was already used") from None
            return existing_manifest


class StaticSasSigner:
    def __init__(self, url: str) -> None:
        self._url = url

    def sign(self, blob_name: str, expires_at: datetime) -> str:
        return self._url.replace("{blobName}", blob_name)


class UserDelegationSasSigner:
    def __init__(self, service_client: BlobServiceClient, settings: Settings) -> None:
        self._service = service_client
        self._settings = settings
        parsed = urlparse(settings.upload_storage_account_url)
        self._account_name = parsed.netloc.split(".")[0]

    def sign(self, blob_name: str, expires_at: datetime) -> str:
        starts_on = datetime.now(UTC) - timedelta(minutes=5)
        delegation_key = self._service.get_user_delegation_key(starts_on, expires_at)
        sas = generate_blob_sas(
            account_name=self._account_name,
            container_name=self._settings.upload_container_name,
            blob_name=blob_name,
            user_delegation_key=delegation_key,
            permission=BlobSasPermissions(create=True, write=True),
            start=starts_on,
            expiry=expires_at,
            protocol="https",
        )
        return f"{self._service.url}/{self._settings.upload_container_name}/{blob_name}?{sas}"


class ConnectionStringSasSigner:
    def __init__(self, settings: Settings) -> None:
        if not settings.storage_connection_string:
            raise ValueError("storage_connection_string is required")
        self._settings = settings
        parts = dict(
            part.split("=", 1)
            for part in settings.storage_connection_string.split(";")
            if "=" in part
        )
        self._account_name = parts.get("AccountName", "devstoreaccount1")
        self._account_key = parts.get("AccountKey")
        self._endpoint = (
            parts.get("BlobEndpoint")
            or f"{settings.upload_storage_account_url.rstrip('/')}"
        )

    def sign(self, blob_name: str, expires_at: datetime) -> str:
        if not self._account_key:
            raise Problem(500, "local_sas_unavailable", "Connection string account key is missing")
        starts_on = datetime.now(UTC) - timedelta(minutes=5)
        sas = generate_blob_sas(
            account_name=self._account_name,
            container_name=self._settings.upload_container_name,
            blob_name=blob_name,
            account_key=self._account_key,
            permission=BlobSasPermissions(create=True, write=True),
            start=starts_on,
            expiry=expires_at,
            protocol="https" if self._endpoint.startswith("https://") else None,
        )
        return f"{self._endpoint.rstrip('/')}/{self._settings.upload_container_name}/{blob_name}?{sas}"


class SasIssuer:
    def __init__(self, settings: Settings, store: IdempotencyStore, signer: SasSigner) -> None:
        self._settings = settings
        self._store = store
        self._signer = signer

    def issue(
        self,
        request: UploadSasRequest,
        claims: Claims,
        now: datetime | None = None,
    ) -> UploadSasResponse:
        now = now or datetime.now(UTC)
        upload_id = build_upload_id(claims, request)
        blob_name = build_blob_name(self._settings, claims, request, now)
        manifest = UploadManifest(
            upload_id=upload_id,
            blob_name=blob_name,
            fingerprint=request.fingerprint,
            content_type=request.content_type,
            content_length_bytes=request.content_length_bytes,
        )
        reserved = self._store.reserve(claims, request, manifest)
        expires_at = now + timedelta(minutes=self._settings.sas_ttl_minutes)
        return UploadSasResponse(
            upload_id=reserved.upload_id,
            blob_name=reserved.blob_name,
            upload_url=self._signer.sign(reserved.blob_name, expires_at),
            expires_at_utc=expires_at,
            max_content_length_bytes=self._settings.max_upload_bytes,
            storage_api_version=self._settings.storage_api_version,
        )


def build_service_client(settings: Settings) -> BlobServiceClient:
    if settings.sas_signer_mode == "connection_string":
        if not settings.storage_connection_string:
            raise ValueError("AZURE_STORAGE_CONNECTION_STRING is required")
        return BlobServiceClient.from_connection_string(settings.storage_connection_string)
    credential = DefaultAzureCredential(managed_identity_client_id=settings.managed_identity_client_id)
    return BlobServiceClient(account_url=settings.upload_storage_account_url, credential=credential)


def ensure_container(service_client: BlobServiceClient, settings: Settings) -> ContainerClient:
    container = service_client.get_container_client(settings.upload_container_name)
    try:
        container.create_container()
    except ResourceExistsError:
        pass
    except ResourceNotFoundError as exc:
        raise Problem(500, "container_unavailable", "Upload container is unavailable") from exc
    return container


def build_issuer(settings: Settings) -> SasIssuer:
    service_client = build_service_client(settings)
    container = ensure_container(service_client, settings)
    store = BlobIdempotencyStore(container, settings)
    signer: SasSigner
    if settings.sas_signer_mode == "connection_string":
        signer = ConnectionStringSasSigner(settings)
    else:
        signer = UserDelegationSasSigner(service_client, settings)
    return SasIssuer(settings, store, signer)
