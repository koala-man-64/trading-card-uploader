from __future__ import annotations

from typing import Any

import jwt
from jwt import PyJWKClient

from .config import Settings
from .models import Claims, Problem


class JwtValidator:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._issuer = f"https://login.microsoftonline.com/{settings.entra_tenant_id}/v2.0"
        self._jwks = PyJWKClient(
            f"https://login.microsoftonline.com/{settings.entra_tenant_id}/discovery/v2.0/keys"
        )

    def validate_authorization_header(self, header: str | None) -> Claims:
        if not header:
            raise Problem(401, "missing_authorization", "Authorization header is required")
        scheme, _, token = header.partition(" ")
        if scheme.lower() != "bearer" or not token:
            raise Problem(401, "invalid_authorization", "Authorization must be a bearer token")
        return self.validate_token(token)

    def validate_token(self, token: str) -> Claims:
        try:
            signing_key = self._jwks.get_signing_key_from_jwt(token)
            decoded = jwt.decode(
                token,
                signing_key.key,
                algorithms=["RS256"],
                audience=list(self._settings.allowed_audiences),
                issuer=self._issuer,
                options={"require": ["aud", "exp", "iat", "iss", "nbf", "tid", "oid"]},
            )
        except jwt.PyJWTError as exc:
            raise Problem(401, "invalid_token", "Access token validation failed") from exc

        claims = self._claims_from_decoded(decoded)
        if (
            self._settings.allowed_android_client_ids
            and claims.authorized_party not in self._settings.allowed_android_client_ids
        ):
            raise Problem(403, "unauthorized_client", "Token client is not allowed")
        return claims

    def _claims_from_decoded(self, decoded: dict[str, Any]) -> Claims:
        tenant_id = str(decoded.get("tid", "")).strip()
        object_id = str(decoded.get("oid", "")).strip()
        audience = str(decoded.get("aud", "")).strip()
        scopes = frozenset(str(decoded.get("scp", "")).split())
        roles = frozenset(str(role) for role in decoded.get("roles", []) if str(role).strip())
        authorized_party = decoded.get("azp") or decoded.get("appid")
        if tenant_id != self._settings.entra_tenant_id:
            raise Problem(401, "wrong_tenant", "Access token tenant is not allowed")
        if not object_id:
            raise Problem(401, "missing_subject", "Access token subject is missing")
        return Claims(
            tenant_id=tenant_id,
            object_id=object_id,
            audience=audience,
            authorized_party=str(authorized_party) if authorized_party else None,
            scopes=scopes,
            roles=roles,
        )
