<#
.SYNOPSIS
    Creates the release signing key for WallFlow and prints everything the
    GitHub Actions build needs.

.DESCRIPTION
    Run this once. It writes:
      keystore\release.jks     the key itself      (never commit this)
      keystore.properties      local build config  (never commit this)
      keystore\github-secrets.txt  the four values to paste into GitHub

    Both output files are already in .gitignore.

.EXAMPLE
    .\tools\create-keystore.ps1
    .\tools\create-keystore.ps1 -Password "my-own-password" -Alias upload
#>
[CmdletBinding()]
param(
    [string]$Password,
    [string]$Alias = "wallflow-upload",
    [string]$CommonName = "WallFlow Personal",
    [int]$ValidityYears = 30
)

$ErrorActionPreference = "Stop"

# Set-Content -Encoding utf8 writes a BOM on Windows PowerShell 5.1, and a BOM
# in keystore.properties ends up glued to the first key name, which makes
# Gradle skip signing without saying why. Write these files BOM-free.
function Set-WallFlowFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(ValueFromPipeline)][string[]]$Line
    )
    begin { $buffer = New-Object System.Collections.Generic.List[string] }
    process { foreach ($l in $Line) { $buffer.Add($l) } }
    end {
        [IO.File]::WriteAllLines($Path, $buffer, (New-Object Text.UTF8Encoding $false))
    }
}

$root = Split-Path -Parent $PSScriptRoot
$keystoreDir = Join-Path $root "keystore"
$keystorePath = Join-Path $keystoreDir "release.jks"

# --- checks -----------------------------------------------------------------

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue)
if ($null -eq $keytool) {
    throw "keytool not found. Install a JDK 17 or newer and make sure it is on PATH."
}

if (Test-Path $keystorePath) {
    Write-Host "A keystore already exists at $keystorePath" -ForegroundColor Yellow
    Write-Host "Delete it only if you are certain the app was never published with it." -ForegroundColor Yellow
    Write-Host "Replacing a published upload key means asking Google to reset it." -ForegroundColor Yellow
    return
}

# --- password ---------------------------------------------------------------

if ([string]::IsNullOrWhiteSpace($Password)) {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $Password = [Convert]::ToBase64String($bytes).Replace("/", "x").Replace("+", "y").Replace("=", "")
    Write-Host "Generated a random 32-character password." -ForegroundColor Cyan
}

if ($Password.Length -lt 6) { throw "The password must be at least 6 characters." }

# --- create -----------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null

$dname = "CN=$CommonName, OU=Personal, O=Personal, L=Bangkok, S=Bangkok, C=TH"

& keytool -genkeypair `
    -v `
    -keystore $keystorePath `
    -storetype JKS `
    -keyalg RSA `
    -keysize 4096 `
    -validity ([int]($ValidityYears * 365)) `
    -alias $Alias `
    -dname $dname `
    -storepass $Password `
    -keypass $Password

if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE." }

# --- local build config -----------------------------------------------------

$propsPath = Join-Path $root "keystore.properties"
@(
    "storeFile=keystore/release.jks",
    "storePassword=$Password",
    "keyAlias=$Alias",
    "keyPassword=$Password"
) | Set-WallFlowFile $propsPath

# --- values for GitHub Actions ----------------------------------------------

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
$secretsPath = Join-Path $keystoreDir "github-secrets.txt"
@(
    "Add these four repository secrets in GitHub:",
    "Settings > Secrets and variables > Actions > New repository secret",
    "",
    "STORE_PASSWORD",
    $Password,
    "",
    "KEY_ALIAS",
    $Alias,
    "",
    "KEY_PASSWORD",
    $Password,
    "",
    "KEYSTORE_BASE64",
    $base64
) | Set-WallFlowFile $secretsPath

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "  Key        : $keystorePath"
Write-Host "  Local conf : $propsPath"
Write-Host "  GitHub     : $secretsPath"
Write-Host ""
Write-Host "BACK UP keystore\release.jks AND THE PASSWORD SOMEWHERE SAFE." -ForegroundColor Red
Write-Host "Losing both means you can no longer update the app under this key." -ForegroundColor Red
