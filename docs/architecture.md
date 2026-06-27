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
