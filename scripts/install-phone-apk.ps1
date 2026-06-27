[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath
)

$resolvedApk = Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    throw "adb was not found on PATH. Install Android platform-tools and enable USB debugging on the phone."
}

& $adb.Source devices
if ($LASTEXITCODE -ne 0) {
    throw "adb devices failed."
}

& $adb.Source install -r $resolvedApk.ProviderPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed for $($resolvedApk.ProviderPath)."
}
