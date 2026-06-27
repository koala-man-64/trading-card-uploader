# Phone-Ready Dev Build

This repo is phone-ready when a physical Android phone can install the dev APK, sign in with Entra/MSAL, capture one card photo, upload it through the dev Function/SAS flow, and show `Complete` in the app.

## One-Time Setup

Configure Entra before building the phone APK:

- API app registration exposes `upload.write` at `api://<api-client-id>/upload.write`.
- Android public client registration uses package `com.tradingcards.uploader`.
- Android redirect URI is `msauth://com.tradingcards.uploader/<dev-signature-hash>`.
- GitHub Azure OIDC app has a federated credential for the `dev` environment.

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

Secrets:

- `ANDROID_DEV_KEYSTORE_B64`
- `ANDROID_DEV_KEYSTORE_PASSWORD`
- `ANDROID_DEV_KEY_ALIAS`
- `ANDROID_DEV_KEY_PASSWORD`

`ANDROID_DEV_KEYSTORE_B64` is the base64-encoded dev signing keystore. Keep it stable so the Entra Android redirect signature hash stays stable.

## Build And Deploy

1. Merge the phone-ready PR only after source checks pass and review is complete.
2. Run `infra-ci` with `deployDev=false` and review the dev what-if.
3. Run `infra-ci` with `deployDev=true` to deploy dev infrastructure.
4. Set or refresh `ANDROID_API_BASE_URL` from the `androidApiBaseUrl` Bicep output.
5. Set or refresh `ANDROID_API_SCOPE` from the `androidApiScope` Bicep output.
6. Set or refresh `FUNCTION_APP_NAME` from the `functionAppName` Bicep output.
7. Run `function-ci` with `deployDev=true`.
8. Run `phone-apk` and download the `phone-dev-apk` artifact.

The `phone-apk` workflow creates the ignored debug MSAL config at build time, signs with the dev keystore secret, validates that no runtime value is a placeholder, and uploads `app-debug.apk` plus non-secret build metadata.

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
6. Verify the displayed blob exists in the private dev container through RBAC.
7. Confirm App Insights has request/trace telemetry for the same upload ID.
8. Confirm telemetry does not contain bearer tokens, SAS URLs, or `sig=` values.
9. Confirm a write-only SAS cannot read, list, or delete blobs.

## Troubleshooting

- If sign-in fails, verify `ANDROID_CLIENT_ID`, `ANDROID_TENANT_ID`, and the Entra redirect URI for the dev signing hash.
- If SAS issuance returns 401 or 403, verify `ANDROID_API_SCOPE`, API app consent, and `ALLOWED_ANDROID_CLIENT_IDS`.
- If the app stays in retry or failed state, inspect the displayed last error and correlate with Function/App Insights traces by upload ID.
- If `phone-apk` fails validation, replace the placeholder or missing GitHub environment value named in the workflow log.
