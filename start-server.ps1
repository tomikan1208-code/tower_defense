$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

# Prefer a project-local Gradle distribution so no machine-wide setup is required.
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

# Locate a JDK 25 (required by this Minestom build). Search the usual install roots,
# not just one vendor, so the script works on a fresh machine.
function Find-Jdk25 {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $version = & (Join-Path $env:JAVA_HOME "bin\java.exe") -version 2>&1 | Select-Object -First 1
        if ($version -match '"(\d+)') {
            if ([int]$Matches[1] -ge 25) { return $env:JAVA_HOME }
        }
    }
    $roots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft\jdk",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Zulu",
        (Join-Path $env:USERPROFILE ".jdks")
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'jdk[-_.]?25' -or $_.Name -match '^25' } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($found -and (Test-Path (Join-Path $found.FullName "bin\java.exe"))) {
            return $found.FullName
        }
    }
    return $null
}

$jdkHome = Find-Jdk25
if (-not $jdkHome) {
    throw "JDK 25 not found. Install it with: winget install --id EclipseAdoptium.Temurin.25.JDK -e"
}

$env:JAVA_HOME = $jdkHome
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

# Stop an existing server holding port 25565 before starting a new one.
$listen = Get-NetTCPConnection -State Listen -LocalPort 25565 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listen) {
    $process = Get-Process -Id $listen.OwningProcess -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host "Stopping existing server (PID=$($process.Id))..."
        Stop-Process -Id $process.Id -Force
    }
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "Starting MAZEWARD. Connect a vanilla Minecraft 1.21.11 client to localhost."
& $gradleBat run
