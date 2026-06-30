# Local Development

## API

```powershell
Set-Location api-sas-issuer
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements-dev.txt
Copy-Item local.settings.sample.json local.settings.json
python -m pytest -q
func start --python
```

`local.settings.json` is intentionally ignored. Put tenant IDs, app IDs, and local storage emulator values there.

Local Azurite mode uses `SAS_SIGNER_MODE=connection_string`. Use the Azurite development storage credential only in your ignored local settings file.
`AZURE_STORAGE_API_VERSION` controls the `x-ms-version` header the Android app must send with blob `PUT` requests. Keep the default `2021-08-06` for Azurite or older-compatible local storage endpoints.
`SCANNER_ADMIN_BASE_URL` must point to the scanner host root for processed and segmented gallery operations, for example `http://127.0.0.1:7072` for a local scanner Function host.

## Android

The checked-in `android-app/app/src/main/res/raw/msal_auth_config.json` is a compile-safe sample. For interactive auth testing, run `scripts/Initialize-DevPhoneEnvironment.ps1`; it creates the ignored debug override at `android-app/app/src/debug/res/raw/msal_auth_config.json` and writes local Gradle auth/signing values to `android-app/local.properties`.

The MSAL redirect URI must match both files below. The JSON and Entra app registration use the URL-encoded hash; the Android manifest path uses the raw hash.

- Debug MSAL config: `msauth://com.tradingcards.uploader/<url-encoded-signature-hash>`
- Android manifest placeholder: `msalRedirectPath=<signature-hash>` in `android-app/local.properties`, or `-PmsalRedirectPath=<signature-hash>`

```powershell
Set-Location android-app
gradle `
  -PuploadApiScope=api://<api-client-id>/upload.write `
  -PgalleryManageScope=api://<api-client-id>/gallery.manage `
  -PmsalRedirectPath=<signature-hash> `
  ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
```

If `android-app/local.properties` already contains the values written by the setup script, the `-PuploadApiScope`, `-PgalleryManageScope`, and `-PmsalRedirectPath` overrides are optional.

The emulator reaches the local Function host at:

```text
http://10.0.2.2:7071/api/
```

Override at build time:

```powershell
gradle `
  -PapiBaseUrl=https://<function-app>.azurewebsites.net/api/ `
  -PuploadApiScope=api://<api-client-id>/upload.write `
  -PgalleryManageScope=api://<api-client-id>/gallery.manage `
  -PmsalRedirectPath=<signature-hash> `
  assembleDebug
```

## Dev Smoke

For a physical phone build and install path, use `docs/phone-ready.md`.

1. Deploy infra to a dev resource group.
2. Deploy the Function App.
3. Install the debug Android build.
4. Sign in with a test Entra user.
5. Capture one test image.
6. Confirm the upload row reaches `Complete`.
7. Verify the blob exists through RBAC-based tooling.
8. Verify App Insights has request and trace telemetry for the same `uploadId`.
