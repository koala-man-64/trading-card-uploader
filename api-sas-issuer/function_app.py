from __future__ import annotations

import json
import logging
from datetime import UTC, datetime
from urllib.parse import urlencode

import azure.functions as func

from shared.auth import JwtValidator
from shared.config import Settings
from shared.gallery import (
    build_container,
    delete_raw_source_group,
    list_raw_images,
    parse_limit,
    raw_image_bytes,
    reprocess_raw_source,
    require_gallery_admin,
    scanner_gallery_images,
    scanner_json,
    scanner_request,
)
from shared.models import Claims, Problem, UploadSasRequest
from shared.sas import build_issuer

app = func.FunctionApp(http_auth_level=func.AuthLevel.ANONYMOUS)


def _json_response(body: dict, status_code: int = 200) -> func.HttpResponse:
    return func.HttpResponse(
        json.dumps(body),
        status_code=status_code,
        mimetype="application/json",
    )


def _authorized_claims(
    req: func.HttpRequest,
    settings: Settings,
    scope: str,
) -> tuple[str, Claims]:
    authorization = req.headers.get("Authorization") or ""
    claims = JwtValidator(settings).validate_authorization_header(authorization)
    claims.require_scope(scope)
    return authorization, claims


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
        _, claims = _authorized_claims(req, settings, settings.required_scope)
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


@app.route(route="v1/admin/gallery/images", methods=["GET"])
def admin_gallery_images(req: func.HttpRequest) -> func.HttpResponse:
    try:
        settings = Settings.from_env()
        authorization, claims = _authorized_claims(req, settings, settings.gallery_manage_scope)
        require_gallery_admin(settings, claims)
        category = str(req.params.get("category", "raw")).strip().lower()
        limit = parse_limit(req.params.get("limit"))
        cursor = req.params.get("cursor")
        if category == "raw":
            payload = list_raw_images(settings, build_container(settings), limit, cursor)
        elif category in {"processed", "segmented"}:
            payload = scanner_gallery_images(settings, authorization, category, limit, cursor)
        else:
            raise Problem(400, "invalid_category", "category must be raw, processed, or segmented")
        logging.info(
            "Listed admin gallery images",
            extra={"custom_dimensions": {"category": category, "count": len(payload.get("items", []))}},
        )
        return _json_response(payload)
    except Problem as problem:
        logging.warning("Admin gallery list rejected: %s", problem.code)
        return _json_response(problem.to_body(), problem.status_code)
    except Exception:
        logging.exception("Unhandled admin gallery list failure")
        return _json_response({"error": "gallery_list_failed", "message": "Gallery images could not be listed"}, 500)


@app.route(route="v1/admin/gallery/image", methods=["GET"])
def admin_gallery_image(req: func.HttpRequest) -> func.HttpResponse:
    try:
        settings = Settings.from_env()
        authorization, claims = _authorized_claims(req, settings, settings.gallery_manage_scope)
        require_gallery_admin(settings, claims)
        category = str(req.params.get("category", "raw")).strip().lower()
        name = str(req.params.get("name", "")).strip()
        if not name:
            raise Problem(400, "missing_blob_name", "name is required")
        if category == "raw":
            data = raw_image_bytes(build_container(settings), name)
            return func.HttpResponse(body=data, status_code=200, mimetype="image/jpeg")
        if category in {"processed", "segmented"}:
            scanner_response = scanner_request(
                settings,
                authorization,
                "GET",
                "/api/v1/admin/gallery/image?"
                + urlencode({"category": category, "name": name}),
            )
            return func.HttpResponse(
                body=scanner_response.body,
                status_code=scanner_response.status_code,
                mimetype=scanner_response.headers.get("Content-Type", "application/octet-stream"),
            )
        raise Problem(400, "invalid_category", "category must be raw, processed, or segmented")
    except Problem as problem:
        logging.warning("Admin gallery image rejected: %s", problem.code)
        return _json_response(problem.to_body(), problem.status_code)
    except Exception:
        logging.exception("Unhandled admin gallery image failure")
        return _json_response({"error": "gallery_image_failed", "message": "Gallery image could not be loaded"}, 500)


@app.route(route="v1/admin/gallery/actions/delete-source-group", methods=["POST"])
def admin_gallery_delete_source_group(req: func.HttpRequest) -> func.HttpResponse:
    try:
        settings = Settings.from_env()
        authorization, claims = _authorized_claims(req, settings, settings.gallery_manage_scope)
        require_gallery_admin(settings, claims)
        payload = req.get_json()
        if not isinstance(payload, dict):
            raise Problem(400, "invalid_json", "Request body must be a JSON object")
        source_blob_name = str(payload.get("sourceBlobName", "")).strip()
        if not source_blob_name:
            raise Problem(400, "missing_source_blob_name", "sourceBlobName is required")
        result = delete_raw_source_group(
            settings,
            authorization,
            build_container(settings),
            source_blob_name,
        )
        logging.info(
            "Deleted admin gallery source group",
            extra={
                "custom_dimensions": {
                    "sourceBlobName": source_blob_name,
                    "rawDeleted": result["rawDeleted"],
                    "scannerSkipped": result["scanner"].get("skipped", False),
                }
            },
        )
        return _json_response(result)
    except Problem as problem:
        logging.warning("Admin gallery delete rejected: %s", problem.code)
        return _json_response(problem.to_body(), problem.status_code)
    except ValueError:
        return _json_response({"error": "invalid_json", "message": "Request body must be JSON"}, 400)
    except Exception:
        logging.exception("Unhandled admin gallery delete failure")
        return _json_response(
            {
                "error": "gallery_delete_failed",
                "message": "Gallery source group could not be deleted",
            },
            500,
        )


@app.route(route="v1/admin/gallery/actions/delete-image", methods=["POST"])
def admin_gallery_delete_image(req: func.HttpRequest) -> func.HttpResponse:
    try:
        settings = Settings.from_env()
        authorization, claims = _authorized_claims(req, settings, settings.gallery_manage_scope)
        require_gallery_admin(settings, claims)
        payload = req.get_json()
        if not isinstance(payload, dict):
            raise Problem(400, "invalid_json", "Request body must be a JSON object")
        category = str(payload.get("category", "")).strip().lower()
        name = str(payload.get("name", "")).strip()
        if category not in {"processed", "segmented"}:
            raise Problem(400, "invalid_category", "category must be processed or segmented")
        if not name:
            raise Problem(400, "missing_blob_name", "name is required")
        result = scanner_json(
            settings,
            authorization,
            "POST",
            "/api/v1/admin/gallery/actions/delete-image",
            {"category": category, "name": name},
        )
        logging.info(
            "Deleted admin gallery image",
            extra={"custom_dimensions": {"category": category, "blobName": name}},
        )
        return _json_response(result)
    except Problem as problem:
        logging.warning("Admin gallery image delete rejected: %s", problem.code)
        return _json_response(problem.to_body(), problem.status_code)
    except ValueError:
        return _json_response({"error": "invalid_json", "message": "Request body must be JSON"}, 400)
    except Exception:
        logging.exception("Unhandled admin gallery image delete failure")
        return _json_response(
            {"error": "gallery_image_delete_failed", "message": "Gallery image could not be deleted"},
            500,
        )


@app.route(route="v1/admin/gallery/actions/reprocess-source", methods=["POST"])
def admin_gallery_reprocess_source(req: func.HttpRequest) -> func.HttpResponse:
    try:
        settings = Settings.from_env()
        authorization, claims = _authorized_claims(req, settings, settings.gallery_manage_scope)
        require_gallery_admin(settings, claims)
        payload = req.get_json()
        if not isinstance(payload, dict):
            raise Problem(400, "invalid_json", "Request body must be a JSON object")
        source_blob_name = str(payload.get("sourceBlobName", "")).strip()
        if not source_blob_name:
            raise Problem(400, "missing_source_blob_name", "sourceBlobName is required")
        result = reprocess_raw_source(
            settings,
            authorization,
            build_container(settings),
            source_blob_name,
        )
        logging.info(
            "Reprocessed admin gallery source",
            extra={"custom_dimensions": {"sourceBlobName": source_blob_name}},
        )
        return _json_response({"sourceBlobName": source_blob_name, "scanner": result})
    except Problem as problem:
        logging.warning("Admin gallery reprocess rejected: %s", problem.code)
        return _json_response(problem.to_body(), problem.status_code)
    except ValueError:
        return _json_response({"error": "invalid_json", "message": "Request body must be JSON"}, 400)
    except Exception:
        logging.exception("Unhandled admin gallery reprocess failure")
        return _json_response(
            {"error": "gallery_reprocess_failed", "message": "Gallery source could not be reprocessed"},
            500,
        )
