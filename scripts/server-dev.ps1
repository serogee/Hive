# Starts the demo server on port 8080.
# Usage: server-dev [--reset]
. (Join-Path $PSScriptRoot 'common.ps1')

$options = @($args)

Invoke-Script {
    foreach ($option in $options) {
        if ($option -ne '--reset') { throw "Unknown option $option. Use: server-dev [--reset]" }
    }
    $env:HIVE_MODE = 'demo'
    $env:HIVE_PORT = '8080'

    if ($options -contains '--reset') {
        Write-Step 'Replacing the demo data'
        Invoke-Gradle @(':server:resetDemo')
    }
    Write-Step 'Starting the demo server. Press Ctrl+C to stop it.'
    Invoke-Gradle @(':server:run', '--console=plain')
}
