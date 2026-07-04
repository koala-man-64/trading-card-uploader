[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$DeviceSerial,
    [switch]$ValidateOnly
)

$resolvedApk = Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop
$packageName = "com.tradingcards.uploader"

function Find-AndroidSdkRoot {
    $candidateRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidateRoots += (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    }

    return $candidateRoots |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
}

function Test-IsWindowsHost {
    return $env:OS -eq "Windows_NT" -or [System.IO.Path]::DirectorySeparatorChar -eq '\'
}

function Find-AndroidTool {
    param([Parameter(Mandatory = $true)][string]$ToolName)

    $tool = Get-Command $ToolName -ErrorAction SilentlyContinue
    if ($tool) {
        return $tool.Source
    }

    $sdkRoot = Find-AndroidSdkRoot
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        return $null
    }

    $buildTools = Join-Path $sdkRoot "build-tools"
    if (-not (Test-Path -LiteralPath $buildTools)) {
        return $null
    }

    $fileName = if (Test-IsWindowsHost) { "$ToolName.bat" } else { $ToolName }
    return Get-ChildItem -LiteralPath $buildTools -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName $fileName } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
}

function Find-Adb {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if ($adb) {
        return $adb.Source
    }

    $sdkRoot = Find-AndroidSdkRoot
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        return $null
    }

    $fileName = if (Test-IsWindowsHost) { "adb.exe" } else { "adb" }
    $adbPath = Join-Path (Join-Path $sdkRoot "platform-tools") $fileName
    if (Test-Path -LiteralPath $adbPath) {
        return $adbPath
    }

    return $null
}

function Ensure-JavaForApkSigner {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or (Get-Command java -ErrorAction SilentlyContinue)) {
        return
    }

    $candidateHomes = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre"
    )

    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidateHomes += (Join-Path $env:LOCALAPPDATA "Programs\Android\Android Studio\jbr")
    }

    $javaHome = $candidateHomes |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Where-Object { Test-Path -LiteralPath (Join-Path $_ "bin\java.exe") } |
        Select-Object -First 1

    if ([string]::IsNullOrWhiteSpace($javaHome)) {
        throw "JAVA_HOME is not set and java was not found on PATH. Set JAVA_HOME before installing so apksigner can verify the APK signing certificate."
    }

    $env:JAVA_HOME = $javaHome
}

function Convert-HexToBytes {
    param([Parameter(Mandatory = $true)][string]$Hex)

    if ($Hex.Length % 2 -ne 0) {
        throw "Invalid SHA-1 digest length from APK signer output."
    }

    $bytes = New-Object byte[] ($Hex.Length / 2)
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        $bytes[$i] = [Convert]::ToByte($Hex.Substring($i * 2, 2), 16)
    }
    return $bytes
}

function Get-ApkSigningHash {
    param([Parameter(Mandatory = $true)][string]$Path)

    Ensure-JavaForApkSigner
    $apksigner = Find-AndroidTool -ToolName "apksigner"
    if (-not $apksigner) {
        throw "apksigner was not found on PATH or under ANDROID_HOME/ANDROID_SDK_ROOT. Refusing to install without verifying the APK signing hash."
    }

    $output = & $apksigner verify --print-certs $Path 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner verification failed for $Path. $($output | Out-String)"
    }

    $sha1Line = $output | Where-Object { $_ -match "certificate SHA-1 digest:\s*([0-9a-fA-F]+)" } | Select-Object -First 1
    $sha1Match = [regex]::Match([string]$sha1Line, "certificate SHA-1 digest:\s*([0-9a-fA-F]+)")
    if (-not $sha1Line -or -not $sha1Match.Success) {
        throw "Could not read APK signing SHA-1 digest from apksigner output."
    }

    return [Convert]::ToBase64String((Convert-HexToBytes -Hex $sha1Match.Groups[1].Value))
}

function Read-ApkMsalConfig {
    param([Parameter(Mandatory = $true)][string]$Path)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq "res/raw/msal_auth_config.json" } | Select-Object -First 1
        if (-not $entry) {
            throw "APK does not contain res/raw/msal_auth_config.json."
        }

        $reader = [System.IO.StreamReader]::new($entry.Open())
        try {
            return $reader.ReadToEnd() | ConvertFrom-Json
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $zip.Dispose()
    }
}

function Test-PlaceholderValue {
    param([string]$Value)

    return [string]::IsNullOrWhiteSpace($Value) -or
        $Value -eq "00000000-0000-0000-0000-000000000000" -or
        $Value -match "placeholder|replace-with"
}

function Assert-PhoneApkAuthConfig {
    param([Parameter(Mandatory = $true)][string]$Path)

    $msalConfig = Read-ApkMsalConfig -Path $Path
    if (Test-PlaceholderValue -Value $msalConfig.client_id) {
        throw "APK MSAL client_id is missing or still contains a placeholder. Use the phone-apk workflow or run Initialize-DevPhoneEnvironment.ps1 before building."
    }

    $tenantId = $msalConfig.authorities[0].audience.tenant_id
    if (Test-PlaceholderValue -Value $tenantId) {
        throw "APK MSAL tenant_id is missing or still contains a placeholder. Use the phone-apk workflow or run Initialize-DevPhoneEnvironment.ps1 before building."
    }

    $redirectUri = [string]$msalConfig.redirect_uri
    if (Test-PlaceholderValue -Value $redirectUri) {
        throw "APK MSAL redirect_uri is missing or still contains a placeholder. Refusing to install an APK that will fail sign-in."
    }

    $signingHash = Get-ApkSigningHash -Path $Path
    $expectedRedirectUri = "msauth://$packageName/$([Uri]::EscapeDataString($signingHash))"
    if ($redirectUri -ne $expectedRedirectUri) {
        throw "APK MSAL redirect_uri does not match the APK signing certificate. Expected $expectedRedirectUri; found $redirectUri."
    }

    Write-Host "APK auth configuration validated for $packageName."
}

Assert-PhoneApkAuthConfig -Path $resolvedApk.ProviderPath
if ($ValidateOnly) {
    return
}

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    $adb = Find-Adb
}
else {
    $adb = $adb.Source
}
if (-not $adb) {
    throw "adb was not found on PATH or under ANDROID_HOME/ANDROID_SDK_ROOT. Install Android platform-tools and enable USB debugging on the phone."
}

$adbArgs = @()
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbArgs += @("-s", $DeviceSerial)
}

& $adb devices
if ($LASTEXITCODE -ne 0) {
    throw "adb devices failed."
}

& $adb @adbArgs install -r $resolvedApk.ProviderPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed for $($resolvedApk.ProviderPath)."
}
