# Upload SAS API Contract

Owner: `api-sas-issuer`

Consumers: `android-app`

## Endpoints

### `GET /api/healthz`

Returns basic service health.

### `POST /api/v1/uploads/sas`

Issues a scoped SAS URL for one image upload.

Headers:

```http
Authorization: Bearer <Entra access token>
Content-Type: application/json
```

Request:

```json
{
  "clientUploadId": "11111111-1111-1111-1111-111111111111",
  "contentType": "image/jpeg",
  "contentLengthBytes": 2450000,
  "sha256Hex": "optional-64-character-hex"
}
```

Response:

```json
{
  "uploadId": "b8abfe11-31fa-5e8e-9d31-14cc0b624b63",
  "blobName": "raw/{tenantHash}/{userHash}/20260627/{uploadId}.jpg",
  "uploadUrl": "https://<account>.blob.core.windows.net/card-uploads/<blob>?<sas>",
  "expiresAtUtc": "2026-06-27T18:20:00Z",
  "requiredHeaders": {
    "x-ms-blob-type": "BlockBlob",
    "Content-Type": "image/jpeg"
  },
  "maxContentLengthBytes": 10485760
}
```

## Validation

- `clientUploadId` must be a UUID.
- `contentType` must be `image/jpeg` or `image/heic`.
- `contentLengthBytes` must be positive and no larger than 10 MiB by default.
- `sha256Hex`, when provided, must be a 64-character hex digest.
- The original filename is not accepted and is never used in the blob path.

## Auth

- Token type: Entra access token.
- Required scope value in the `scp` claim: `upload.write`.
- Required claims: `iss`, `aud`, `exp`, `nbf`, `tid`, and `oid`.
- Optional client allowlist: `azp` or `appid` must match configured Android client IDs when configured.

## Errors

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `invalid_client_upload_id` | `clientUploadId` is missing or not a UUID. |
| 400 | `unsupported_content_type` | Content type is not allowed. |
| 400 | `invalid_content_length` | Content length is missing or invalid. |
| 400 | `invalid_sha256` | Checksum is malformed. |
| 401 | `missing_authorization` | No bearer token was supplied. |
| 401 | `invalid_token` | Token validation failed. |
| 403 | `missing_scope` | Token lacks `upload.write`. |
| 403 | `unauthorized_client` | Token client is not allowed. |
| 409 | `idempotency_conflict` | Same `clientUploadId` was used with different upload metadata. |
| 413 | `content_too_large` | Content exceeds the configured limit. |
