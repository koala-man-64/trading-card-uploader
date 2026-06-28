from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from .config import Settings

_SHA256_RE = re.compile(r"^[a-fA-F0-9]{64}$")
AZURE_STORAGE_API_VERSION = "2023-11-03"


class Problem(Exception):
    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message

    def to_body(self) -> dict[str, str]:
        return {"error": self.code, "message": self.message}


@dataclass(frozen=True)
class Claims:
    tenant_id: str
    object_id: str
    audience: str
    authorized_party: str | None
    scopes: frozenset[str]

    def require_scope(self, scope: str) -> None:
        if scope not in self.scopes:
            raise Problem(403, "missing_scope", f"Required scope is missing: {scope}")


@dataclass(frozen=True)
class UploadSasRequest:
    client_upload_id: str
    content_type: str
    content_length_bytes: int
    sha256_hex: str | None

    @classmethod
    def from_json(cls, payload: dict[str, Any], settings: Settings) -> UploadSasRequest:
        if not isinstance(payload, dict):
            raise Problem(400, "invalid_json", "Request body must be a JSON object")

        client_upload_id = str(payload.get("clientUploadId", "")).strip()
        try:
            uuid.UUID(client_upload_id)
        except ValueError as exc:
            raise Problem(400, "invalid_client_upload_id", "clientUploadId must be a UUID") from exc

        content_type = str(payload.get("contentType", "")).strip().lower()
        if content_type not in settings.allowed_content_types:
            raise Problem(400, "unsupported_content_type", "Only JPEG and HEIC images are allowed")

        content_length = payload.get("contentLengthBytes")
        if not isinstance(content_length, int):
            raise Problem(400, "invalid_content_length", "contentLengthBytes must be an integer")
        if content_length <= 0:
            raise Problem(400, "invalid_content_length", "contentLengthBytes must be positive")
        if content_length > settings.max_upload_bytes:
            raise Problem(413, "content_too_large", "Image exceeds the configured upload limit")

        sha256_hex = payload.get("sha256Hex")
        if sha256_hex is not None:
            sha256_hex = str(sha256_hex).strip().lower()
            if not _SHA256_RE.match(sha256_hex):
                raise Problem(400, "invalid_sha256", "sha256Hex must be a 64-character hex digest")

        return cls(
            client_upload_id=client_upload_id,
            content_type=content_type,
            content_length_bytes=content_length,
            sha256_hex=sha256_hex,
        )

    @property
    def file_extension(self) -> str:
        if self.content_type == "image/heic":
            return "heic"
        return "jpg"

    @property
    def fingerprint(self) -> str:
        checksum = self.sha256_hex or "no-checksum"
        return f"{self.content_type}:{self.content_length_bytes}:{checksum}"


@dataclass(frozen=True)
class UploadSasResponse:
    upload_id: str
    blob_name: str
    upload_url: str
    expires_at_utc: datetime
    max_content_length_bytes: int

    def to_json(self) -> dict[str, Any]:
        return {
            "uploadId": self.upload_id,
            "blobName": self.blob_name,
            "uploadUrl": self.upload_url,
            "expiresAtUtc": self.expires_at_utc.astimezone(UTC)
            .replace(microsecond=0)
            .isoformat()
            .replace("+00:00", "Z"),
            "requiredHeaders": {
                "x-ms-blob-type": "BlockBlob",
                "x-ms-version": AZURE_STORAGE_API_VERSION,
                "Content-Type": "image/jpeg" if self.blob_name.endswith(".jpg") else "image/heic",
            },
            "maxContentLengthBytes": self.max_content_length_bytes,
        }
