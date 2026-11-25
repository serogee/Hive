# Builds the server and runs the built server, as the hosting service does.
# Reads environment variables from server\.env when that file exists.
. (Join-Path $PSScriptRoot 'common.ps1')

$serverArguments = @($args)

Invoke-Script {
    Write-Step 'Building the server'
    Invoke-Gradle @(':server:installDist')

    $server = Join-Path $Root 'server'
    $envFile = Join-Path $server '.env'
    if (Test-Path $envFile) {
        $values = Read-Properties $envFile
        foreach ($name in $values.Keys) {
            $value = $values[$name] -replace '^"(.*)"$', '$1'
            Set-Item -Path "Env:$name" -Value $value
        }
        # Names only, because the values can be secrets.
        Write-Host "From server\.env: $(($values.Keys | Sort-Object) -join ', ')"
    } else {
        Write-Host 'No server\.env file, so the server uses its defaults. Copy server\.env.example to make one.'
    }

    Write-Step 'Starting the server. Press Ctrl+C to stop it.'
    # Relative paths, such as the demo database, start in the server folder, as with Gradle.
    Set-Location $server
    Invoke-Program (Join-Path $server 'build\install\server\bin\server.bat') $serverArguments 'The server stopped with an error.'
}
