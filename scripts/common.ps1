# Shared by the scripts that the .cmd files in the repository root start.
# Works in Windows PowerShell 5.1. Keep this file and the others ASCII only.

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$AppId = 'io.github.serogee.hive'

# Runs a script's work, prints a failure as one clear line, and sets the exit code.
function Invoke-Script([scriptblock]$Work) {
    $code = 0
    Push-Location $Root
    try {
        & $Work
    } catch {
        Write-Host ''
        Write-Host "Stopped: $($_.Exception.Message)" -ForegroundColor Red
        $code = 1
    } finally {
        Pop-Location
    }
    Wait-IfDoubleClicked
    exit $code
}

# Keeps the window open when the .cmd file was double-clicked in Explorer.
function Wait-IfDoubleClicked {
    try {
        $self = Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
        $cmd = Get-CimInstance Win32_Process -Filter "ProcessId=$($self.ParentProcessId)"
        $starter = Get-CimInstance Win32_Process -Filter "ProcessId=$($cmd.ParentProcessId)"
        if ($cmd.Name -eq 'cmd.exe' -and $starter.Name -eq 'explorer.exe') {
            Read-Host 'Press Enter to close' | Out-Null
        }
    } catch {
        # The window closes by itself, which is the normal behavior.
    }
}

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host "== $Text" -ForegroundColor Yellow
}

# Runs a program and stops the script when it fails.
function Invoke-Program([string]$Program, [string[]]$Arguments, [string]$Failure) {
    # Programs such as git report progress on stderr, which is not a failure.
    $ErrorActionPreference = 'Continue'
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit code $LASTEXITCODE)" }
}

# Runs a program and returns its output lines, or nothing when it fails. Wrap the call in @().
function Read-Program([string]$Program, [string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & $Program @Arguments 2>$null
    if ($LASTEXITCODE -eq 0) { $output }
}

# Runs a program quietly and returns whether it succeeds.
function Test-Program([string]$Program, [string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    & $Program @Arguments 2>$null | Out-Null
    return $LASTEXITCODE -eq 0
}

function Invoke-Gradle([string[]]$Arguments) {
    Invoke-Program (Join-Path $Root 'gradlew.bat') $Arguments 'Gradle failed. See the output above.'
}

# Finds a program of the Android SDK, or a program on PATH.
function Find-AndroidTool([string]$RelativePath, [string]$Name) {
    $folders = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $local = Join-Path $Root 'local.properties'
    if (Test-Path $local) {
        $line = Get-Content $local | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { $folders = @(($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\(.)', '$1') + $folders }
    }
    if ($env:LOCALAPPDATA) { $folders += Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    foreach ($folder in $folders) {
        if ($folder -and (Test-Path (Join-Path $folder $RelativePath))) { return Join-Path $folder $RelativePath }
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    return $null
}

# The Gradle settings of this Windows user, which are never in the repository.
function Get-UserGradleProperties {
    $gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    return Join-Path $gradleHome 'gradle.properties'
}

function Read-Properties([string]$Path) {
    $values = @{}
    if (Test-Path $Path) {
        foreach ($line in Get-Content $Path) {
            if ($line -match '^\s*([^#!=\s][^=]*?)\s*=\s*(.*?)\s*$') { $values[$Matches[1]] = $Matches[2] }
        }
    }
    return $values
}
