# Runs the JVM tests and builds the debug app.
. (Join-Path $PSScriptRoot 'common.ps1')

Invoke-Script {
    Write-Step 'Running the tests and building the debug app'
    Invoke-Gradle @('test', ':client:assembleDebug')

    $apk = Join-Path $Root 'client\build\outputs\apk\debug\client-debug.apk'
    if (-not (Test-Path $apk)) { throw "Gradle finished, but there is no app at $apk" }
    Write-Host ''
    Write-Host "Debug app: $apk" -ForegroundColor Green
}
