# Architecture

```text
Android app
  Camera capture
  Room upload queue
  WorkManager upload worker
  MSAL auth
        |
        | Bearer access token
        v
Azure Functions Python v2 SAS issuer
  JWT validation
  request validation
  idempotency manifest
  user-delegation SAS generation
        |
        | create/write SAS
        v
Azure Blob Storage upload container
```

The API does not proxy image bytes. It only validates the caller, chooses a server-generated blob path, reserves the idempotency key, and returns a short-lived SAS URL for one blob.

## Scanner Ingestion Contract

- Original image uploads are written under `raw/{tenantHash}/{userHash}/{yyyyMMdd}/{uploadId}.<ext>`.
- Idempotency manifests are written under `manifests/{tenantHash}/{userHash}/{clientUploadId}.json`.
- `trading-card-scanner` must watch only the `raw/` prefix of this upload container.
- The scanner owns crop extraction and gallery output; this repo owns capture, authentication, and write-only upload authorization.
- The SAS issuer proxies two read paths from the scanner for gallery-admin callers: gallery listings (`v1/admin/gallery/*`) and readiness (`GET v1/admin/scanner/status`, which wraps the scanner's `/api/ready` and reports `configured`/`reachable`/`ready` plus the scanner's component payload verbatim). The Android Monitor tab consumes the status endpoint and joins raw uploads to processed crops via `sourceBlobName`, which the scanner derives from its lineage manifests.

## Trust Boundary

- Android is a public client and must never contain confidential credentials.
- The Function accepts Entra access tokens for the upload API scope.
- The Function managed identity signs user-delegation SAS URLs and has only the storage roles required for SAS issuance and manifest writes.
- Blob containers are private; the returned SAS must not grant read, list, or delete access.

## Reliability Boundary

- Android persists each capture before network work starts.
- WorkManager retries network, 408, 429, and 5xx failures.
- The Function uses `clientUploadId` for idempotency.
- A reused `clientUploadId` with different content metadata returns 409.
