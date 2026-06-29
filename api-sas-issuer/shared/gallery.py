from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from azure.core.exceptions import ResourceNotFoundError
from azure.storage.blob import ContainerClient

from .config import Settings
from .models import Claims, Problem
from .sas import (
    ConnectionStringSasSigner,
    UserDelegationSasSigner,
    build_service_client,
    ensure_container,
)

IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif")


@dataclass(frozen=True)
class ScannerResponse:
    status_code: int
    headers: dict[str, str]
    body: bytes


def require_gallery_admin(settings: Settings, claims: Claims) -> None:
    claims.require_scope(settings.gallery_manage_scope)
    claims.require_admin(settings.admin_allowed_object_ids, settings.admin_allowed_roles)


def build_container(settings: Settings) -> ContainerClient:
    return ensure_container(build_service_client(settings), settings)


def build_read_signer(settings: Settings) -> ConnectionStringSasSigner | UserDelegationSasSigner:
    service_client = build_service_client(settings)
    if settings.sas_signer_mode == "connection_string":
        return ConnectionStringSasSigner(settings)
    return UserDelegationSasSigner(service_client, settings)


def is_image_blob(blob_name: str) -> bool:
    return blob_name.lower().endswith(IMAGE_EXTENSIONS)


def require_raw_image_blob_name(blob_name: str) -> None:
    if not blob_name.startswith("raw/") or not is_image_blob(blob_name):
        raise Problem(
            400,
            "invalid_raw_blob_name",
            "sourceBlobName must reference a raw image blob",
        )


def parse_limit(value: str | None, default: int = 50) -> int:
    if not value:
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    return max(1, min(parsed, 100))


def list_raw_images(
    settings: Settings,
    container: ContainerClient,
    limit: int,
    cursor: str | None,
) -> dict[str, Any]:
    blobs = [
        blob
        for blob in container.list_blobs(name_starts_with="raw/")
        if is_image_blob(blob.name)
    ]
    start = int(cursor) if cursor and cursor.isdigit() else 0
    page = blobs[start : start + limit]
    next_cursor = str(start + limit) if start + limit < len(blobs) else None
    signer = build_read_signer(settings)
    expires_at = datetime.now(UTC) + timedelta(minutes=settings.sas_ttl_minutes)
    items = []
    for blob in page:
        modified = getattr(blob, "last_modified", None)
        modified_utc = modified.astimezone(UTC) if modified else None
        items.append(
            {
                "category": "raw",
                "name": blob.name,
                "sourceBlobName": blob.name,
                "size": blob.size or 0,
                "lastModifiedUtc": modified_utc.isoformat().replace("+00:00", "Z")
                if modified_utc
                else None,
                "previewUrl": signer.sign_read(blob.name, expires_at),
                "canCascade": True,
            }
        )
    return {"category": "raw", "items": items, "nextCursor": next_cursor}


def ensure_scanner_configured(settings: Settings) -> None:
    if not settings.scanner_admin_base_url:
        raise Problem(
            500,
            "scanner_not_configured",
            "SCANNER_ADMIN_BASE_URL is required for scanner gallery operations",
        )


def scanner_request(
    settings: Settings,
    authorization: str,
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
) -> ScannerResponse:
    ensure_scanner_configured(settings)
    data = None
    headers = {"Authorization": authorization}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = Request(
        f"{settings.scanner_admin_base_url}{path}",
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urlopen(request, timeout=settings.scanner_timeout_seconds) as response:
            return ScannerResponse(
                status_code=response.status,
                headers=dict(response.headers.items()),
                body=response.read(),
            )
    except HTTPError as exc:
        return ScannerResponse(
            status_code=exc.code,
            headers=dict(exc.headers.items()),
            body=exc.read(),
        )


def scanner_json(
    settings: Settings,
    authorization: str,
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    response = scanner_request(settings, authorization, method, path, payload)
    body = json.loads(response.body.decode("utf-8") or "{}")
    if response.status_code >= 400:
        raise Problem(
            response.status_code,
            str(body.get("error", "scanner_request_failed")),
            str(body.get("message", "Scanner request failed")),
        )
    if not isinstance(body, dict):
        raise Problem(502, "invalid_scanner_response", "Scanner returned invalid JSON")
    return body


def scanner_gallery_images(
    settings: Settings,
    authorization: str,
    category: str,
    limit: int,
    cursor: str | None,
) -> dict[str, Any]:
    params = {"category": category, "limit": str(limit)}
    if cursor:
        params["cursor"] = cursor
    payload = scanner_json(
        settings,
        authorization,
        "GET",
        f"/api/v1/admin/gallery/images?{urlencode(params)}",
    )
    for item in payload.get("items", []):
        if isinstance(item, dict):
            item["previewUrl"] = (
                "/api/v1/admin/gallery/image?"
                + urlencode({"category": category, "name": str(item.get("name", ""))})
            )
    return payload


def delete_raw_blob(container: ContainerClient, blob_name: str) -> bool:
    require_raw_image_blob_name(blob_name)
    try:
        container.get_blob_client(blob_name).delete_blob()
        return True
    except ResourceNotFoundError:
        return False


def delete_raw_source_group(
    settings: Settings,
    authorization: str,
    container: ContainerClient,
    source_blob_name: str,
) -> dict[str, Any]:
    require_raw_image_blob_name(source_blob_name)
    if settings.scanner_admin_base_url:
        scanner_payload = scanner_json(
            settings,
            authorization,
            "POST",
            "/api/v1/admin/gallery/actions/delete-by-source",
            {"sourceBlobName": source_blob_name},
        )
    else:
        scanner_payload = {"skipped": True, "reason": "scanner_not_configured"}
    raw_deleted = delete_raw_blob(container, source_blob_name)
    return {
        "sourceBlobName": source_blob_name,
        "rawDeleted": raw_deleted,
        "scanner": scanner_payload,
    }


def raw_image_bytes(container: ContainerClient, blob_name: str) -> bytes:
    require_raw_image_blob_name(blob_name)
    try:
        return container.get_blob_client(blob_name).download_blob().readall()
    except ResourceNotFoundError as exc:
        raise Problem(404, "raw_blob_not_found", "Raw source blob was not found") from exc


def reprocess_raw_source(
    settings: Settings,
    authorization: str,
    container: ContainerClient,
    source_blob_name: str,
) -> dict[str, Any]:
    image_bytes = raw_image_bytes(container, source_blob_name)
    return scanner_json(
        settings,
        authorization,
        "POST",
        "/api/v1/admin/gallery/actions/reprocess-source",
        {
            "sourceBlobName": source_blob_name,
            "imageBytesBase64": base64.b64encode(image_bytes).decode("ascii"),
        },
    )
