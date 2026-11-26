# Installs the debug app on the connected phones or emulators, and opens it.
# Usage: .\run app dev [server URL]
. (Join-Path $PSScriptRoot 'common.ps1')

$serverUrl = $args | Select-Object -First 1

Invoke-Script {
    $adb = Find-AndroidTool 'platform-tools\adb.exe' 'adb'
    if (-not $adb) { throw 'adb was not found. Install the Android SDK platform tools, or set sdk.dir in local.properties.' }

    Write-Step 'Looking for a phone or an emulator'
    $lines = @(Read-Program $adb @('devices'))
    if ($lines.Count -eq 0) { throw 'adb could not list the devices. Run adb kill-server and try again.' }
    $ready = @()
    $notReady = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device\s*$') {
            $ready += $Matches[1]
        } elseif ($line -match '^(\S+)\s+(unauthorized|offline)\s*$') {
            $notReady += "$($Matches[1]) is $($Matches[2])"
        }
    }
    if ($ready.Count -eq 0) {
        if ($notReady.Count -gt 0) {
            throw "No device is ready: $($notReady -join ', '). Unlock the phone and allow USB debugging."
        }
        throw 'No phone or emulator is connected. Turn on USB debugging and connect the phone, or start an emulator.'
    }
    Write-Host "Devices: $($ready -join ', ')"

    Write-Step 'Building and installing the debug app'
    $gradle = @(':client:installDebug')
    if ($serverUrl) {
        if ($serverUrl -notmatch '^https?://') { $serverUrl = "http://$serverUrl" }
        Write-Host "Default server: $serverUrl. A server address that is saved in the app stays in use."
        $gradle += "-Phive.serverUrl=$serverUrl"
    }
    Invoke-Gradle $gradle

    Write-Step 'Opening Hive'
    foreach ($device in $ready) {
        Invoke-Program $adb @('-s', $device, 'shell', 'am', 'start', '-n', "$AppId/.MainActivity") "Hive did not open on $device"
    }
}
