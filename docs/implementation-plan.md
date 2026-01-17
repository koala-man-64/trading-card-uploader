# Implementation Plan (Orchestrator)

## Goal
Deliver an Android app that captures photos, persists locally, and uploads to Azure Blob Storage using short-lived SAS URLs issued by an Azure Functions API, with CI/CD and IaC in GitHub.

## Orchestrator Summary
This plan consolidates inputs from all skills and agents, resolves dependencies, and sequences delivery to maximize security, reliability, and operability.

## Architecture Overview (Condensed)
1. Android app captures photos via CameraX and writes to app-private storage.
2. Local Room DB tracks upload jobs with state transitions: Pending → Uploading → Uploaded/Failed.
3. WorkManager performs background uploads with retry/backoff.
4. Upload authorization is requested from a SAS Issuer API (Azure Functions) using Entra authentication.
5. App uploads directly to Azure Blob Storage via PUT using a scoped, short-lived SAS URL.

## Implementation Phases

### Phase 0 — Repo Structure and Governance
- Create repo layout:
  - /android-app
  - /api-sas-issuer
  - /infra
  - /.github/workflows
  - /docs
- Define branch protections, CODEOWNERS, and PR required checks.

### Phase 1 — Azure Infrastructure (IaC)
- Provision resource group, storage account, container, and Function App.
- Enable managed identity on Function App.
- Apply RBAC: Storage Blob Delegator for user delegation SAS.
- Configure Function App authentication (Easy Auth / Entra).
- Optional: diagnostic settings, soft delete, lifecycle policies.

### Phase 2 — SAS Issuer API (Azure Functions)
- Implement `/v1/uploads:authorize` endpoint:
  - Validate content type, size, and path rules.
  - Enforce per-user prefix.
  - Issue user delegation SAS with minimal scope and short expiry.
- Optional `/v1/uploads:complete` endpoint for audit.
- Add structured logs and Application Insights.

### Phase 3 — Android App (Capture + Offline Queue)
- CameraX screen to capture and persist file.
- Room schema for upload jobs.
- WorkManager worker for background upload:
  - Request SAS
  - PUT upload via OkHttp streaming
  - Update DB state and retry on transient errors
- Optional status UI for uploads.

### Phase 4 — CI/CD
- Android CI: build, test, lint.
- Infra deploy with GitHub OIDC.
- Function build/deploy pipeline.
- Smoke tests for Function API.

### Phase 5 — Operational Readiness
- Runbooks for incident response and recovery.
- Monitoring/alerting setup.
- Load testing and capacity sizing.

## Risks and Mitigations
- **Credential leakage**: Use Entra auth + user delegation SAS; no account keys in app.
- **Offline failure**: Persist upload jobs locally and retry with backoff.
- **Upload duplication**: Use unique blob naming or conditional headers.
- **Background constraints**: WorkManager constraints and optional foreground service for reliability.

## Dependencies
- Entra ID tenant configuration for Function App auth.
- Azure RBAC roles for SAS issuance.
- Mobile authentication method (MSAL or enterprise SSO).

## Agent Feedback, Input, and Signoff

### Product Management
- **Input**: Prioritize offline-first UX and status screen; allow upload retry visibility.
- **Feedback**: Ensure upload status is transparent for users and support.
- **Signoff**: Approved.

### Android Engineering
- **Input**: Use CameraX ImageCapture; WorkManager for background uploads; Room for job persistence.
- **Feedback**: Prefer streaming upload; avoid storing large images in memory.
- **Signoff**: Approved.

### Backend Engineering (SAS Issuer)
- **Input**: Keep API minimal, enforce strict validation, and use user delegation SAS.
- **Feedback**: Ensure short expiry and single-blob scope; return required headers.
- **Signoff**: Approved.

### Infrastructure Engineering
- **Input**: Use Bicep with GitHub OIDC for deployments; enable managed identity.
- **Feedback**: Role assignments must allow user delegation SAS issuance.
- **Signoff**: Approved.

### Security
- **Input**: No shared keys in app; short-lived SAS only; enforce HTTPS.
- **Feedback**: Consider disabling shared key authorization after validation.
- **Signoff**: Approved.

### QA/Testing
- **Input**: Validate offline capture → online upload; test retry and SAS expiry.
- **Feedback**: Add automated smoke tests for Function endpoint.
- **Signoff**: Approved.

### Operations/SRE
- **Input**: Configure Application Insights, storage diagnostics, and alerts.
- **Feedback**: Runbooks required for failed uploads and auth errors.
- **Signoff**: Approved.

## Skills Checklist (All Skills Engaged)
- **Architecture**: Completed.
- **Security**: Completed.
- **Mobile**: Completed.
- **Backend**: Completed.
- **Infrastructure/IaC**: Completed.
- **CI/CD**: Completed.
- **Operations**: Completed.
- **QA**: Completed.

## Acceptance Criteria
- Upload flow works end-to-end with short-lived SAS.
- Offline photos persist and upload automatically when online.
- Function rejects unauthenticated requests.
- CI/CD runs for app, infra, and functions.
- Monitoring and runbooks are in place.
