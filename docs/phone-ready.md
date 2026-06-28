# Phone-Ready Dev Build

This repo is phone-ready when a physical Android phone can install the dev APK, sign in with Entra/MSAL, capture one card photo, upload it through the dev Function/SAS flow, and show `Complete` in the app.

## One-Time Setup

Configure Entra before building the phone APK:

- API app registration exposes `upload.write` at `api://<api-client-id>/upload.write`.
- Android public client registration uses package `com.tradingcards.uploader`.
- Android redirect URI is `msauth://com.tradingcards.uploader/<dev-signature-hash>`.
- GitHub Azure OIDC app has a federated credential for the `dev` environment.

The repo-provided setup script can create or update those dev registrations, generate or reuse a stable ignored dev signing keystore, compute the MSAL signature hash, create the GitHub `dev` environment, and write the known GitHub environment values:

```powershell
.\scripts\Initialize-DevPhoneEnvironment.ps1 `
  -ResourceGroup <dev-resource-group> `
  -GrantAdminConsent `
  -AssignAzureRoles
```

The script stores the reusable dev keystore and local metadata under `.local/phone-dev/`, which is ignored. Keep that keystore stable so the Entra Android redirect signature hash stays stable. `-AssignAzureRoles` assigns the GitHub OIDC service principal the dev resource-group permissions needed to deploy infrastructure; omit it if those roles are managed elsewhere.

Configure the GitHub `dev` environment:

Variables:

- `AZURE_CLIENT_ID`
- `AZURE_TENANT_ID`
- `AZURE_SUBSCRIPTION_ID`
- `AZURE_RESOURCE_GROUP`
- `API_CLIENT_ID`
- `API_APP_ID_URI`
- `ANDROID_CLIENT_ID`
- `ANDROID_TENANT_ID`
- `ANDROID_API_BASE_URL`
- `ANDROID_API_SCOPE`
- `ANDROID_MSAL_SIGNATURE_HASH`
- `FUNCTION_APP_NAME`
- `SMOKE_PRINCIPAL_ID`
- `UPLOAD_STORAGE_ACCOUNT_NAME`
- `UPLOAD_CONTAINER_NAME`
- `APP_INSIGHTS_NAME`

Secrets:

- `ANDROID_DEV_KEYSTORE_B64`
- `ANDROID_DEV_KEYSTORE_PASSWORD`
- `ANDROID_DEV_KEY_ALIAS`
- `ANDROID_DEV_KEY_PASSWORD`

`Initialize-DevPhoneEnvironment.ps1` sets the app-registration and signing values. `Sync-DevInfraOutputs.ps1` sets or refreshes the deploy-derived values after infrastructure deployment.

## Build And Deploy

1. Merge the phone-ready PR only after source checks pass and review is complete.
2. Run `infra-ci` with `deployDev=false` and review the dev what-if.
3. Run `infra-ci` with `deployDev=true` to deploy dev infrastructure.
4. Sync the dev deployment outputs back to the GitHub `dev` environment:

   ```powershell
   .\scripts\Sync-DevInfraOutputs.ps1 -ResourceGroup <dev-resource-group>
   ```

5. Run `function-ci` with `deployDev=true`.
6. Run `phone-apk` and download the `phone-dev-apk` artifact.

The `phone-apk` workflow creates the ignored debug MSAL config at build time, signs with the dev keystore secret, verifies `ANDROID_MSAL_SIGNATURE_HASH` against the dev keystore, URL-encodes the hash only in the MSAL JSON redirect URI, validates that no runtime value is a placeholder, and uploads `app-debug.apk` plus non-secret build metadata.

## Install

Enable USB debugging on the phone and install:

```powershell
.\scripts\install-phone-apk.ps1 -ApkPath .\app-debug.apk
```

Or run the equivalent platform-tools command:

```powershell
adb install -r .\app-debug.apk
```

## Smoke Checklist

1. Open the app on the phone.
2. Tap `Sign in` and authenticate with a dev test user.
3. Tap `Capture card photo` and take one test photo.
4. Wait for the latest upload panel to show `Status: Complete`.
5. Record the displayed client upload ID, server upload ID, and blob name.
6. Run `phone-smoke-verify` with the client upload ID, server upload ID, blob name, and optional UTC lower bound.
7. Confirm the workflow summary says the blob exists through RBAC, App Insights telemetry matched, and telemetry bearer/SAS marker checks are clean.

Write-only SAS behavior is proven by the Function unit tests so the app and smoke workflow do not need to expose SAS URLs.

## Troubleshooting

- If sign-in fails, verify `ANDROID_CLIENT_ID`, `ANDROID_TENANT_ID`, and the Entra redirect URI for the dev signing hash.
- If SAS issuance returns 401 or 403, verify `ANDROID_API_SCOPE`, API app consent, and `ALLOWED_ANDROID_CLIENT_IDS`.
- If the app stays in retry or failed state, inspect the displayed last error and correlate with Function/App Insights traces by upload ID.
- If `phone-apk` fails validation, replace the placeholder or missing GitHub environment value named in the workflow log.
- If `phone-apk` reports a signing hash mismatch, rerun `Initialize-DevPhoneEnvironment.ps1` with the same `.local/phone-dev/` keystore or update the Entra Android redirect URI to the current hash.
- If `phone-smoke-verify` cannot query blob or telemetry evidence, verify `SMOKE_PRINCIPAL_ID` was passed to `infra-ci` and that the dev deployment has completed with smoke-reader RBAC assignments.
