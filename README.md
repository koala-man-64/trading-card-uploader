# trading-card-uploader

Android app and Azure backend for capturing trading-card photos and uploading them directly to Azure Blob Storage with short-lived, scoped SAS URLs.

## Status

This repo now contains the first vertical-slice implementation:

- `android-app/` - Android Kotlin app with MSAL sign-in, local upload queue, and background upload worker.
- `api-sas-issuer/` - Azure Functions Python v2 API that validates Entra access tokens and issues scoped upload SAS URLs.
- `infra/` - Bicep resources for Function hosting, upload storage, managed identity, RBAC, and observability.
- `docs/` - API contract, local development, state machine, and operations notes.

## Flow

1. Android captures a card photo into app-private storage.
2. WorkManager reads the durable Room queue.
3. Android gets an Entra access token with MSAL.
4. Android calls `POST /api/v1/uploads/sas`.
5. The Function validates the token and request, reserves the idempotency key, and returns a short-lived blob SAS.
6. Android uploads the image bytes directly to Blob Storage.
7. Android marks the local upload complete after Blob Storage returns success.

## Scanner Integration

This app is the capture-ingest exception to the scanner's normal HTTP-first
Android guidance. It uploads original images under `raw/` in the private upload
container, while SAS idempotency metadata is stored under `manifests/`.
Configure `trading-card-scanner` to watch only the `raw/` prefix so it processes
captured images and ignores metadata blobs.

See [docs/contracts/upload-sas-api.md](docs/contracts/upload-sas-api.md) and [docs/local-dev.md](docs/local-dev.md).
