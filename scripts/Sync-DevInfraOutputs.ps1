[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Repo = "koala-man-64/trading-card-uploader",
    [string]$Environment = "dev",
    [Parameter(Mandatory = $true)]
    [string]$ResourceGroup,
    [string]$DeploymentName = "trading-card-uploader-dev",
    [string]$SubscriptionId
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

function Invoke-GhText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return Invoke-External -FilePath "gh" -Arguments $Arguments
}

function Get-OutputValue {
    param(
        [Parameter(Mandatory = $true)][object]$Outputs,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $property = $Outputs.PSObject.Properties[$Name]
    if (-not $property -or [string]::IsNullOrWhiteSpace([string]$property.Value.value)) {
        throw "Deployment output '$Name' is missing from deployment '$DeploymentName'."
    }
    return [string]$property.Value.value
}

function Set-GitHubEnvironmentVariable {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Value for GitHub environment variable $Name is empty."
    }
    if ($PSCmdlet.ShouldProcess("$Repo/$Environment variable $Name", "set GitHub environment variable")) {
        Invoke-GhText @("variable", "set", $Name, "--repo", $Repo, "--env", $Environment, "--body", $Value) | Out-Null
    }
}

Get-Command az -ErrorAction Stop | Out-Null
Get-Command gh -ErrorAction Stop | Out-Null

if (-not [string]::IsNullOrWhiteSpace($SubscriptionId)) {
    Invoke-External -FilePath "az" -Arguments @("account", "set", "--subscription", $SubscriptionId) | Out-Null
}

$deployment = Invoke-AzJson @(
    "deployment", "group", "show",
    "--resource-group", $ResourceGroup,
    "--name", $DeploymentName
)
$outputs = $deployment.properties.outputs

$values = [ordered]@{
    ANDROID_API_BASE_URL = Get-OutputValue -Outputs $outputs -Name "androidApiBaseUrl"
    ANDROID_API_SCOPE = Get-OutputValue -Outputs $outputs -Name "androidApiScope"
    ANDROID_GALLERY_MANAGE_SCOPE = Get-OutputValue -Outputs $outputs -Name "androidGalleryManageScope"
    FUNCTION_APP_NAME = Get-OutputValue -Outputs $outputs -Name "functionAppName"
    UPLOAD_STORAGE_ACCOUNT_NAME = Get-OutputValue -Outputs $outputs -Name "uploadStorageAccountName"
    UPLOAD_CONTAINER_NAME = Get-OutputValue -Outputs $outputs -Name "uploadContainerName"
    APP_INSIGHTS_NAME = Get-OutputValue -Outputs $outputs -Name "appInsightsName"
}

foreach ($entry in $values.GetEnumerator()) {
    Set-GitHubEnvironmentVariable -Name $entry.Key -Value $entry.Value
}

Write-Host "Synced dev deployment outputs from '$DeploymentName' to GitHub environment '$Environment'."
Write-Host "Updated variables: $($values.Keys -join ', ')"
