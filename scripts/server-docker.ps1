# Runs the production server with Postgres in Docker, as it runs when it is hosted.
# Usage: .\run server docker            builds the server and starts both, until Ctrl+C
#        .\run server docker --down     stops them and keeps the data
#        .\run server docker --reset    stops them and deletes the data, after a question
. (Join-Path $PSScriptRoot 'common.ps1')

$option = if ($args.Count -gt 0) { [string]$args[0] } else { '' }

Invoke-Script {
    if ($option -and $option -notin '--down', '--reset') { throw "Unknown option $option. Use: .\run server docker [--down | --reset]" }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker was not found. Install Docker Desktop.' }
    if (-not (Test-Program 'docker' @('info'))) { throw 'Docker is not running. Start Docker Desktop and try again.' }

    switch ($option) {
        '--down' {
            Write-Step 'Stopping the server and the database'
            Invoke-Program 'docker' @('compose', 'down') 'Docker could not stop them.'
        }
        '--reset' {
            $answer = Read-Host 'Delete every room and account in the local database? (y/n)'
            if ($answer -notmatch '^(y|yes)$') {
                Write-Host 'Nothing was deleted.'
                return
            }
            Write-Step 'Stopping the server and deleting the database'
            Invoke-Program 'docker' @('compose', 'down', '--volumes') 'Docker could not delete the database.'
        }
        default {
            if (-not (Test-Path (Join-Path $Root '.env'))) {
                Write-Host 'No .env file, so the defaults in compose.yaml are used. Copy .env.example to change them.'
            }
            Write-Step 'Building the server and starting it with Postgres. Press Ctrl+C to stop.'
            Invoke-Program 'docker' @('compose', 'up', '--build') 'Docker stopped with an error.'
        }
    }
}
