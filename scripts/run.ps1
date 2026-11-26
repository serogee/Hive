# Runs Hive's commands by name, like npm run. Usage: run <group> <command> [options]
# Each command is one line in $Commands and one script in this folder. The script gets every option unchanged.
. (Join-Path $PSScriptRoot 'common.ps1')

$Commands = [ordered]@{
    'app build'     = @{ Script = 'app-build.ps1'; Options = ''; Help = 'Runs the JVM tests and builds the debug app.' }
    'app dev'       = @{ Script = 'app-dev.ps1'; Options = '[server URL]'; Help = 'Installs the debug app on the connected phone or emulator and opens it.' }
    'app release'   = @{ Script = 'app-release.ps1'; Options = '<version> | --setup-key'; Help = 'Publishes a signed release from main, or makes the signing key one time.' }
    'server dev'    = @{ Script = 'server-dev.ps1'; Options = '[--reset]'; Help = 'Starts the demo server on port 8080, with fresh demo data after --reset.' }
    'server run'    = @{ Script = 'server-run.ps1'; Options = ''; Help = 'Builds the server and runs it with the settings in server\.env.' }
    'server docker' = @{ Script = 'server-docker.ps1'; Options = '[--down | --reset]'; Help = 'Runs the server image with Postgres in Docker. --reset deletes the local database.' }
}

function Show-Commands {
    Write-Host 'Usage: .\run <group> <command> [options]'
    Write-Host ''
    $width = ($Commands.Keys | ForEach-Object { "$_ $($Commands[$_].Options)".Trim().Length } | Measure-Object -Maximum).Maximum
    foreach ($name in $Commands.Keys) {
        $usage = "$name $($Commands[$name].Options)".Trim()
        Write-Host ('  .\run {0}  {1}' -f $usage.PadRight($width), $Commands[$name].Help)
    }
}

# Runs the script of [name], and ends with the exit code of that script.
function Start-HiveCommand([string]$Name, [string[]]$Options) {
    & (Join-Path $PSScriptRoot $Commands[$Name].Script) @Options
    exit $LASTEXITCODE
}

# A double-click opens a numbered menu, because Explorer gives no options.
function Show-Menu {
    $names = @($Commands.Keys)
    Write-Host 'Hive commands'
    Write-Host ''
    for ($i = 0; $i -lt $names.Count; $i++) {
        Write-Host ('  {0}. {1,-14} {2}' -f ($i + 1), $names[$i], $Commands[$names[$i]].Help)
    }
    Write-Host ''
    $choice = Read-Host 'Number, or Enter to close'
    if (-not $choice) { return }
    $index = 0
    if (-not [int]::TryParse($choice, [ref]$index) -or $index -lt 1 -or $index -gt $names.Count) {
        Write-Host "There is no command $choice." -ForegroundColor Red
        Read-Host 'Press Enter to close' | Out-Null
        exit 1
    }
    $name = $names[$index - 1]
    $options = @()
    if ($Commands[$name].Options) {
        $typed = Read-Host "Options for $name $($Commands[$name].Options), or Enter for none"
        if ($typed) { $options = @($typed -split '\s+' | Where-Object { $_ }) }
    }
    # The script waits for Enter at its end, because the window was double-clicked.
    Start-HiveCommand $name $options
}

if ($args.Count -eq 0) {
    if (Test-DoubleClicked) { Show-Menu } else { Show-Commands }
    exit 0
}
if ($args.Count -eq 1 -and $args[0] -in 'help', '--help', '-h', '/?') {
    Show-Commands
    exit 0
}

$name = if ($args.Count -ge 2) { "$($args[0]) $($args[1])" } else { [string]$args[0] }
if (-not $Commands.Contains($name)) {
    Write-Host "Unknown command: $($args -join ' ')" -ForegroundColor Red
    Write-Host ''
    Show-Commands
    exit 1
}
$options = if ($args.Count -gt 2) { @($args[2..($args.Count - 1)]) } else { @() }
Start-HiveCommand $name $options
