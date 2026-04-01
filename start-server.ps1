$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

# Prefer local Gradle distribution to avoid machine-wide setup requirements.
$gradleVersion = "9.1.0"
$gradleHome = Join-Path $projectRoot ".tools\gradle\gradle-$gradleVersion"
$gradleZip = Join-Path $projectRoot ".tools\gradle\gradle-$gradleVersion-bin.zip"
$gradleBat = Join-Path $gradleHome "bin\gradle.bat"

if (-not (Test-Path $gradleBat)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $gradleZip) | Out-Null
    $url = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
    Write-Host "Downloading Gradle $gradleVersion..."
    Invoke-WebRequest -Uri $url -OutFile $gradleZip
    Expand-Archive -Path $gradleZip -DestinationPath (Split-Path -Parent $gradleHome) -Force
}

# Use JDK 25 for this Minestom version.
$jdkDir = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-25*" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jdkDir) {
    throw "JDK 25 not found. Install with: winget install --id EclipseAdoptium.Temurin.25.JDK -e"
}

$env:JAVA_HOME = $jdkDir.FullName
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

# 既存のサーバーが25565を使っていたら止めてから起動する
$listen = Get-NetTCPConnection -State Listen -LocalPort 25565 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listen) {
    $process = Get-Process -Id $listen.OwningProcess -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host "Stopping existing server (PID=$($process.Id))..."
        Stop-Process -Id $process.Id -Force
    }
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
& $gradleBat run
