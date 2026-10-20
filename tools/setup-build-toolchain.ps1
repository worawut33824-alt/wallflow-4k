<#
.SYNOPSIS
    Installs the Android SDK command-line tools and Gradle so the project can
    be built from a plain terminal, without Android Studio.

.DESCRIPTION
    Everything lands in the standard locations, so installing Android Studio
    later picks the same SDK up instead of downloading a second copy:

      %LOCALAPPDATA%\Android\Sdk          Android SDK
      %LOCALAPPDATA%\Gradle\gradle-8.14.2 Gradle

    Safe to re-run: each step is skipped when its target already exists.
#>
[CmdletBinding()]
param(
    [string]$GradleVersion = "8.14.2",
    [string]$CmdlineToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # a progress bar makes downloads much slower

$sdkRoot    = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$gradleRoot = Join-Path $env:LOCALAPPDATA "Gradle"
$gradleHome = Join-Path $gradleRoot "gradle-$GradleVersion"
$downloads  = Join-Path $env:TEMP "wallflow-toolchain"

New-Item -ItemType Directory -Force -Path $downloads | Out-Null

# --- Android command-line tools ---------------------------------------------

$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkManager)) {
    Write-Host "Downloading Android command-line tools..." -ForegroundColor Cyan
    $zip = Join-Path $downloads "cmdline-tools.zip"
    Invoke-WebRequest -Uri $CmdlineToolsUrl -OutFile $zip

    $staging = Join-Path $downloads "cmdline-staging"
    if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
    Expand-Archive -Path $zip -DestinationPath $staging -Force

    # The archive unpacks as cmdline-tools\, but sdkmanager insists on
    # living under cmdline-tools\latest\.
    $target = Join-Path $sdkRoot "cmdline-tools\latest"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Move-Item -Path (Join-Path $staging "cmdline-tools") -Destination $target
    Write-Host "Installed to $target" -ForegroundColor Green
} else {
    Write-Host "Android command-line tools already present." -ForegroundColor DarkGray
}

# --- SDK packages ------------------------------------------------------------

$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot

Write-Host "Accepting SDK licences..." -ForegroundColor Cyan
$yes = ("y`n" * 50)
$yes | & $sdkManager --licenses --sdk_root="$sdkRoot" | Out-Null

Write-Host "Installing platform-tools, android-36 and build-tools..." -ForegroundColor Cyan
& $sdkManager --sdk_root="$sdkRoot" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE." }

# --- Gradle ------------------------------------------------------------------

if (-not (Test-Path (Join-Path $gradleHome "bin\gradle.bat"))) {
    Write-Host "Downloading Gradle $GradleVersion..." -ForegroundColor Cyan
    $zip = Join-Path $downloads "gradle-$GradleVersion-bin.zip"
    Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $zip
    New-Item -ItemType Directory -Force -Path $gradleRoot | Out-Null
    Expand-Archive -Path $zip -DestinationPath $gradleRoot -Force
    Write-Host "Installed to $gradleHome" -ForegroundColor Green
} else {
    Write-Host "Gradle $GradleVersion already present." -ForegroundColor DarkGray
}

# --- Point the project at the SDK -------------------------------------------

$root = Split-Path -Parent $PSScriptRoot
$localProps = Join-Path $root "local.properties"
"sdk.dir=" + $sdkRoot.Replace("\", "\\") | Set-Content -Path $localProps -Encoding utf8

Write-Host ""
Write-Host "Toolchain ready." -ForegroundColor Green
Write-Host "  SDK    : $sdkRoot"
Write-Host "  Gradle : $gradleHome\bin\gradle.bat"
Write-Host ""
Write-Host "Build with:"
Write-Host "  & `"$gradleHome\bin\gradle.bat`" assembleDebug"
