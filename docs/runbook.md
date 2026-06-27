# Runbook

## Signals

Monitor:

- Function 5xx rate.
- Function 401/403 rate.
- SAS issuance count and rejection count.
- Blob upload success and failure count.
- Android crash rate.
- Upload queue age and retry count once mobile telemetry is wired.

## Triage

1. Check Function App availability and recent deployments.
2. Query App Insights for failed `POST /api/v1/uploads/sas` requests.
3. Check rejected request codes: auth failures, validation failures, idempotency conflicts, or storage failures.
4. Check upload storage logs for failed writes.
5. Use `uploadId` to correlate Android logs, Function traces, and blob path.

## Rollback

- Function: redeploy the previous package artifact.
- Infra: redeploy the previous known-good Bicep template and parameter set.
- Upload endpoint incident: remove the Function identity's upload-storage RBAC assignment or disable the endpoint until fixed.
- Storage data recovery: use soft delete and retention where available.

Production deployment approval remains a manual user decision.
