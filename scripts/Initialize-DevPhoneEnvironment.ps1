[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Repo = "koala-man-64/trading-card-uploader",
    [string]$Environment = "dev",
    [string]$PackageName = "com.tradingcards.uploader",
    [Parameter(Mandatory = $true)]
    [string]$ResourceGroup,
    [string]$SubscriptionId,
    [string]$TenantId,
    [string]$ApiAppDisplayName = "trading-card-uploader-dev-api",
    [string]$AndroidAppDisplayName = "trading-card-uploader-dev-android",
    [string]$GitHubOidcAppDisplayName = "trading-card-uploader-dev-github-oidc",
    [string]$ApiClientId,
    [string]$ApiAppIdUri,
    [string]$AndroidClientId,
    [string]$GitHubClientId,
    [string]$KeystorePath = ".local/phone-dev/dev-upload.jks",
    [string]$KeystoreMetadataPath = ".local/phone-dev/dev-upload.metadata.json",
    [string]$KeyAlias = "upload-dev",
    [string]$DevKeystorePassword,
    [string]$DevKeyPassword,
    [switch]$GrantAdminConsent,
    [switch]$AssignAzureRoles
)

$ErrorActionPreference = "Stop"

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$Json
    )

    $output = & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath command failed with exit code $LASTEXITCODE."
    }

    if ($Json) {
        $text = ($output | Out-String).Trim()
        if ([string]::IsNullOrWhiteSpace($text) -or $text -eq "null") {
            return $null
        }
        return $text | ConvertFrom-Json
    }

    return $output
}

function Invoke-AzJson {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return Invoke-External -FilePath "az" -Arguments ($Arguments + @("--output", "json")) -Json
}

function Invoke-AzText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return Invoke-External -FilePath "az" -Arguments $Arguments
}

function Invoke-GhText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return Invoke-External -FilePath "gh" -Arguments $Arguments
}

function Get-AppByDisplayName {
    param([Parameter(Mandatory = $true)][string]$DisplayName)
    return Invoke-AzJson @("ad", "app", "list", "--display-name", $DisplayName, "--query", "[0]")
}

function Get-AppByClientId {
    param([Parameter(Mandatory = $true)][string]$ClientId)
    return Invoke-AzJson @("ad", "app", "show", "--id", $ClientId)
}

function Ensure-AppRegistration {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DisplayName,
        [string]$ClientId,
        [switch]$PublicClient
    )

    if (-not [string]::IsNullOrWhiteSpace($ClientId)) {
        return Get-AppByClientId -ClientId $ClientId
    }

    $existing = Get-AppByDisplayName -DisplayName $DisplayName
    if ($existing) {
        return $existing
    }

    $args = @(
        "ad", "app", "create",
        "--display-name", $DisplayName,
        "--sign-in-audience", "AzureADMyOrg"
    )
    if ($PublicClient) {
        $args += @("--is-fallback-public-client", "true")
    }
    else {
        $args += @("--requested-access-token-version", "2")
    }

    if ($PSCmdlet.ShouldProcess($DisplayName, "create Entra app registration")) {
        return Invoke-AzJson $args
    }

    throw "WhatIf stopped before creating an app registration."
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][object]$Value
    )

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $json = $Value | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($Path, $json, [System.Text.Encoding]::UTF8)
}

function Invoke-GraphPatch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApplicationObjectId,
        [Parameter(Mandatory = $true)]
        [hashtable]$Body,
        [Parameter(Mandatory = $true)]
        [string]$TargetName
    )

    $tempFile = [System.IO.Path]::GetTempFileName()
    try {
        Write-JsonFile -Path $tempFile -Value $Body
        if ($PSCmdlet.ShouldProcess($TargetName, "patch Entra app registration")) {
            Invoke-AzText @(
                "rest",
                "--method", "PATCH",
                "--uri", "https://graph.microsoft.com/v1.0/applications/$ApplicationObjectId",
                "--headers", "Content-Type=application/json",
                "--body", "@$tempFile"
            ) | Out-Null
        }
    }
    finally {
        Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
    }
}

function Ensure-ServicePrincipal {
    param([Parameter(Mandatory = $true)][string]$ClientId)

    $sp = $null
    $output = & az ad sp show --id $ClientId --output json 2>$null
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($output | Out-String))) {
        $sp = (($output | Out-String).Trim() | ConvertFrom-Json)
    }

    if ($sp) {
        return $sp
    }

    if ($PSCmdlet.ShouldProcess("Entra application", "create service principal")) {
        return Invoke-AzJson @("ad", "sp", "create", "--id", $ClientId)
    }

    throw "WhatIf stopped before creating a service principal."
}

function New-ProtectedText {
    $bytes = [byte[]]::new(32)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "A").Replace("/", "B")
}

function Find-Keytool {
    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\keytool.exe"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
        $candidate = Join-Path $env:JAVA_HOME "bin/keytool"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "keytool was not found on PATH or under JAVA_HOME."
}

function Ensure-DevKeystore {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$MetadataPath,
        [Parameter(Mandatory = $true)][string]$Alias,
        [string]$StorePassword,
        [string]$KeyPassword
    )

    $resolvedPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    $resolvedMetadataPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($MetadataPath)
    $metadata = $null
    if (Test-Path -LiteralPath $resolvedMetadataPath) {
        $metadata = [System.IO.File]::ReadAllText($resolvedMetadataPath) | ConvertFrom-Json
    }

    if ($metadata) {
        return [pscustomobject]@{
            Path = $resolvedPath
            Alias = $metadata.keyAlias
            StorePassword = $metadata.storePassword
            KeyPassword = $metadata.keyPassword
        }
    }

    if (Test-Path -LiteralPath $resolvedPath) {
        if ([string]::IsNullOrWhiteSpace($StorePassword) -or [string]::IsNullOrWhiteSpace($KeyPassword)) {
            throw "Keystore metadata is missing."
        }
    }
    else {
        $StorePassword = if ([string]::IsNullOrWhiteSpace($StorePassword)) { New-ProtectedText } else { $StorePassword }
        $KeyPassword = if ([string]::IsNullOrWhiteSpace($KeyPassword)) { New-ProtectedText } else { $KeyPassword }

        $keytool = Find-Keytool
        New-Item -ItemType Directory -Path (Split-Path -Parent $resolvedPath) -Force | Out-Null
        if ($PSCmdlet.ShouldProcess($resolvedPath, "generate stable dev Android signing keystore")) {
            & $keytool @(
                "-genkeypair",
                "-v",
                "-keystore", $resolvedPath,
                "-alias", $Alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-storepass", $StorePassword,
                "-keypass", $KeyPassword,
                "-dname", "CN=Trading Card Uploader Dev,O=Trading Card Uploader,C=US",
                "-storetype", "JKS"
            ) | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "keytool failed to generate the dev phone keystore."
            }
        }
    }

    $record = [pscustomobject]@{
        keyAlias = $Alias
        storePassword = $StorePassword
        keyPassword = $KeyPassword
        createdUtc = (Get-Date).ToUniversalTime().ToString("o")
    }
    if ($PSCmdlet.ShouldProcess($resolvedMetadataPath, "write ignored dev keystore metadata")) {
        Write-JsonFile -Path $resolvedMetadataPath -Value $record
    }

    return [pscustomobject]@{
        Path = $resolvedPath
        Alias = $Alias
        StorePassword = $StorePassword
        KeyPassword = $KeyPassword
    }
}

function Get-MsalSignatureHash {
    param(
        [Parameter(Mandatory = $true)][string]$KeystoreFile,
        [Parameter(Mandatory = $true)][string]$Alias,
        [Parameter(Mandatory = $true)][string]$StorePassword
    )

    $keytool = Find-Keytool
    $certPem = & $keytool @(
        "-exportcert",
        "-keystore", $KeystoreFile,
        "-alias", $Alias,
        "-storepass", $StorePassword,
        "-rfc"
    )
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed to export the dev phone signing certificate."
    }

    $certBase64 = ($certPem | Where-Object {
            $_ -and $_ -notmatch "^-+BEGIN CERTIFICATE-+$" -and $_ -notmatch "^-+END CERTIFICATE-+$"
        }) -join ""
    $certBytes = [Convert]::FromBase64String($certBase64)
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    try {
        return [Convert]::ToBase64String($sha1.ComputeHash($certBytes))
    }
    finally {
        $sha1.Dispose()
    }
}

function Update-GitHubEnvironmentVariable {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "A GitHub environment variable value is empty."
    }
    if ($PSCmdlet.ShouldProcess("$Repo/$Environment variable $Name", "update GitHub environment variable")) {
        Invoke-GhText @("variable", "set", $Name, "--repo", $Repo, "--env", $Environment, "--body", $Value) | Out-Null
    }
}

function Update-GitHubEnvironmentProtectedValue {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "A protected GitHub environment value is empty."
    }
    if ($PSCmdlet.ShouldProcess("$Repo/$Environment protected value $Name", "update GitHub environment value")) {
        Invoke-GhText @("secret", "set", $Name, "--repo", $Repo, "--env", $Environment, "--body", $Value) | Out-Null
    }
}

function Ensure-RoleAssignment {
    param(
        [Parameter(Mandatory = $true)][string]$PrincipalObjectId,
        [Parameter(Mandatory = $true)][string]$Scope,
        [Parameter(Mandatory = $true)][string]$Role
    )

    $existing = Invoke-AzJson @(
        "role", "assignment", "list",
        "--assignee", $PrincipalObjectId,
        "--scope", $Scope,
        "--role", $Role,
        "--query", "[0]"
    )
    if ($existing) {
        return
    }

    if ($PSCmdlet.ShouldProcess("GitHub OIDC service principal", "assign Azure role $Role")) {
        Invoke-AzJson @(
            "role", "assignment", "create",
            "--assignee-object-id", $PrincipalObjectId,
            "--assignee-principal-type", "ServicePrincipal",
            "--scope", $Scope,
            "--role", $Role
        ) | Out-Null
    }
}

Get-Command az -ErrorAction Stop | Out-Null
Get-Command gh -ErrorAction Stop | Out-Null

$account = Invoke-AzJson @("account", "show")
if ([string]::IsNullOrWhiteSpace($TenantId)) {
    $TenantId = $account.tenantId
}
if ([string]::IsNullOrWhiteSpace($SubscriptionId)) {
    $SubscriptionId = $account.id
}

$keystore = Ensure-DevKeystore -Path $KeystorePath -MetadataPath $KeystoreMetadataPath -Alias $KeyAlias -StorePassword $DevKeystorePassword -KeyPassword $DevKeyPassword
$signatureHash = Get-MsalSignatureHash -KeystoreFile $keystore.Path -Alias $keystore.Alias -StorePassword $keystore.StorePassword

$apiApp = Ensure-AppRegistration -DisplayName $ApiAppDisplayName -ClientId $ApiClientId
$ApiClientId = $apiApp.appId
if ([string]::IsNullOrWhiteSpace($ApiAppIdUri)) {
    $ApiAppIdUri = "api://$ApiClientId"
}

$existingScopes = @($apiApp.api.oauth2PermissionScopes)
$uploadScope = $existingScopes | Where-Object { $_.value -eq "upload.write" } | Select-Object -First 1
if (-not $uploadScope) {
    $uploadScope = [pscustomobject]@{
        adminConsentDescription = "Allow the application to upload trading card photos."
        adminConsentDisplayName = "Upload trading card photos"
        id = ([guid]::NewGuid()).ToString()
        isEnabled = $true
        type = "User"
        userConsentDescription = "Upload trading card photos."
        userConsentDisplayName = "Upload trading card photos"
        value = "upload.write"
    }
}
$scopes = @($existingScopes | Where-Object { $_.value -ne "upload.write" }) + @($uploadScope)
Invoke-GraphPatch -ApplicationObjectId $apiApp.id -TargetName $ApiAppDisplayName -Body @{
    identifierUris = @($ApiAppIdUri)
    api = @{
        requestedAccessTokenVersion = 2
        oauth2PermissionScopes = $scopes
    }
}
$apiApp = Get-AppByClientId -ClientId $ApiClientId
$uploadScope = @($apiApp.api.oauth2PermissionScopes) | Where-Object { $_.value -eq "upload.write" } | Select-Object -First 1
Ensure-ServicePrincipal -ClientId $ApiClientId | Out-Null

$androidApp = Ensure-AppRegistration -DisplayName $AndroidAppDisplayName -ClientId $AndroidClientId -PublicClient
$AndroidClientId = $androidApp.appId
$redirectUri = "msauth://$PackageName/$signatureHash"
Invoke-GraphPatch -ApplicationObjectId $androidApp.id -TargetName $AndroidAppDisplayName -Body @{
    isFallbackPublicClient = $true
    publicClient = @{
        redirectUris = @($redirectUri)
    }
    requiredResourceAccess = @(
        @{
            resourceAppId = $ApiClientId
            resourceAccess = @(
                @{
                    id = $uploadScope.id
                    type = "Scope"
                }
            )
        }
    )
}
Ensure-ServicePrincipal -ClientId $AndroidClientId | Out-Null

if ($GrantAdminConsent) {
    if ($PSCmdlet.ShouldProcess("Android app registration", "grant admin consent for API permissions")) {
        Invoke-AzText @("ad", "app", "permission", "admin-consent", "--id", $AndroidClientId) | Out-Null
    }
}

$githubApp = Ensure-AppRegistration -DisplayName $GitHubOidcAppDisplayName -ClientId $GitHubClientId
$GitHubClientId = $githubApp.appId
$githubSp = Ensure-ServicePrincipal -ClientId $GitHubClientId

$credentialName = "github-$Environment-environment"
$credential = [ordered]@{
    name = $credentialName
    issuer = "https://token.actions.githubusercontent.com"
    subject = "repo:$Repo`:environment:$Environment"
    description = "GitHub Actions OIDC for $Repo $Environment environment"
    audiences = @("api://AzureADTokenExchange")
}
$credentialFile = [System.IO.Path]::GetTempFileName()
try {
    Write-JsonFile -Path $credentialFile -Value $credential
    $existingCredential = Invoke-AzJson @(
        "ad", "app", "federated-credential", "list",
        "--id", $GitHubClientId,
        "--query", "[?name=='$credentialName'] | [0]"
    )
    if ($existingCredential) {
        if ($PSCmdlet.ShouldProcess($credentialName, "update federated credential")) {
            Invoke-AzJson @(
                "ad", "app", "federated-credential", "update",
                "--id", $GitHubClientId,
                "--federated-credential-id", $credentialName,
                "--parameters", "@$credentialFile"
            ) | Out-Null
        }
    }
    elseif ($PSCmdlet.ShouldProcess($credentialName, "create federated credential")) {
        Invoke-AzJson @(
            "ad", "app", "federated-credential", "create",
            "--id", $GitHubClientId,
            "--parameters", "@$credentialFile"
        ) | Out-Null
    }
}
finally {
    Remove-Item -LiteralPath $credentialFile -Force -ErrorAction SilentlyContinue
}

if ($AssignAzureRoles) {
    $resourceGroupScope = "/subscriptions/$SubscriptionId/resourceGroups/$ResourceGroup"
    Ensure-RoleAssignment -PrincipalObjectId $githubSp.id -Scope $resourceGroupScope -Role "Contributor"
    Ensure-RoleAssignment -PrincipalObjectId $githubSp.id -Scope $resourceGroupScope -Role "User Access Administrator"
}

if ($PSCmdlet.ShouldProcess("$Repo environment $Environment", "ensure GitHub environment exists")) {
    Invoke-GhText @("api", "-X", "PUT", "repos/$Repo/environments/$Environment") | Out-Null
}

$variables = [ordered]@{
    AZURE_CLIENT_ID = $GitHubClientId
    AZURE_TENANT_ID = $TenantId
    AZURE_SUBSCRIPTION_ID = $SubscriptionId
    AZURE_RESOURCE_GROUP = $ResourceGroup
    API_CLIENT_ID = $ApiClientId
    API_APP_ID_URI = $ApiAppIdUri
    ANDROID_CLIENT_ID = $AndroidClientId
    ANDROID_TENANT_ID = $TenantId
    ANDROID_API_SCOPE = "$ApiAppIdUri/upload.write"
    ANDROID_MSAL_SIGNATURE_HASH = $signatureHash
    SMOKE_PRINCIPAL_ID = $githubSp.id
}
foreach ($entry in $variables.GetEnumerator()) {
    Update-GitHubEnvironmentVariable -Name $entry.Key -Value $entry.Value
}

$keystoreBytes = [System.IO.File]::ReadAllBytes($keystore.Path)
Update-GitHubEnvironmentProtectedValue -Name "ANDROID_DEV_KEYSTORE_B64" -Value ([Convert]::ToBase64String($keystoreBytes))
Update-GitHubEnvironmentProtectedValue -Name "ANDROID_DEV_KEYSTORE_PASSWORD" -Value $keystore.StorePassword
Update-GitHubEnvironmentProtectedValue -Name "ANDROID_DEV_KEY_ALIAS" -Value $keystore.Alias
Update-GitHubEnvironmentProtectedValue -Name "ANDROID_DEV_KEY_PASSWORD" -Value $keystore.KeyPassword
