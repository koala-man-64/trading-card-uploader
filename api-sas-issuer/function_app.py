from __future__ import annotations

import json
import logging
from datetime import UTC, datetime

import azure.functions as func

from shared.auth import JwtValidator
from shared.config import Settings
from shared.models import Problem, UploadSasRequest
from shared.sas import build_issuer

app = func.FunctionApp(http_auth_level=func.AuthLevel.ANONYMOUS)


def _json_response(body: dict, status_code: int = 200) -> func.HttpResponse:
    return func.HttpResponse(
        json.dumps(body),
        status_code=status_code,
        mimetype="application/json",
    )


@app.route(route="healthz", methods=["GET"])
def healthz(req: func.HttpRequest) -> func.HttpResponse:
    return _json_response(
        {
            "status": "ok",
            "service": "api-sas-issuer",
            "utc": datetime.now(UTC).replace(microsecond=0).isoformat(),
        }
    )


@app.route(route="v1/uploads/sas", methods=["POST"])
def issue_upload_sas(req: func.HttpRequest) -> func.HttpResponse:
    try:
        settings = Settings.from_env()
        claims = JwtValidator(settings).validate_authorization_header(req.headers.get("Authorization"))
        claims.require_scope(settings.required_scope)
        upload_request = UploadSasRequest.from_json(req.get_json(), settings)
        response = build_issuer(settings).issue(upload_request, claims)
        logging.info(
            "Issued upload SAS",
            extra={
                "custom_dimensions": {
                    "uploadId": response.upload_id,
                    "blobName": response.blob_name,
                    "contentType": upload_request.content_type,
                    "contentLengthBytes": upload_request.content_length_bytes,
                    "expiresAtUtc": response.expires_at_utc.isoformat(),
                }
            },
        )
        return _json_response(response.to_json())
    except ValueError as exc:
        logging.exception("Configuration or request parsing failure")
        return _json_response({"error": "server_configuration_error", "message": str(exc)}, 500)
    except Problem as problem:
        logging.warning("SAS request rejected: %s", problem.code)
        return _json_response(problem.to_body(), problem.status_code)
    except Exception:
        logging.exception("Unhandled SAS issuance failure")
        return _json_response(
            {"error": "sas_issuance_failed", "message": "Upload SAS could not be issued"},
            500,
        )
