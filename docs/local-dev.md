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

## Android

The checked-in `android-app/app/src/main/res/raw/msal_auth_config.json` is a compile-safe sample. For interactive auth testing, create an ignored debug override at `android-app/app/src/debug/res/raw/msal_auth_config.json` with the dev Entra Android app registration values.

The MSAL redirect URI must match both files below:

- Debug MSAL config: `msauth://com.tradingcards.uploader/<signature-hash>`
- Android manifest placeholder: `-PmsalRedirectPath=<signature-hash>`

```powershell
Set-Location android-app
gradle `
  -PapiScope=api://<api-client-id>/upload.write `
  -PmsalRedirectPath=<signature-hash> `
  ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
```

The emulator reaches the local Function host at:

```text
http://10.0.2.2:7071/api/
```

Override at build time:

```powershell
gradle `
  -PapiBaseUrl=https://<function-app>.azurewebsites.net/api/ `
  -PapiScope=api://<api-client-id>/upload.write `
  -PmsalRedirectPath=<signature-hash> `
  assembleDebug
```

## Dev Smoke

1. Deploy infra to a dev resource group.
2. Deploy the Function App.
3. Install the debug Android build.
4. Sign in with a test Entra user.
5. Capture one test image.
6. Confirm the upload row reaches `Complete`.
7. Verify the blob exists through RBAC-based tooling.
8. Verify App Insights has request and trace telemetry for the same `uploadId`.
