# Makes a release from main: tests, a signed app, a tag, and a GitHub release.
# Usage: .\run app release <version>, for example .\run app release 0.1.0
#        .\run app release --setup-key, once, to make the signing key
. (Join-Path $PSScriptRoot 'common.ps1')

$command = $args | Select-Object -First 1
$SigningNames = 'hive.signing.storeFile', 'hive.signing.storePassword', 'hive.signing.keyAlias', 'hive.signing.keyPassword'

function Find-Keytool {
    if ($env:JAVA_HOME) {
        $tool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        if (Test-Path $tool) { return $tool }
    }
    $found = Get-Command keytool -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { return $found.Source }
    throw 'keytool was not found. Install a JDK, or set JAVA_HOME.'
}

# Makes the signing key one time, and saves its settings for this Windows user only.
function New-SigningKey {
    $keytool = Find-Keytool
    $propertiesFile = Get-UserGradleProperties
    $existing = Read-Properties $propertiesFile
    foreach ($name in $SigningNames) {
        if ($existing.ContainsKey($name)) {
            throw "A signing key is already set up in $propertiesFile. A new key could not update apps that are already installed."
        }
    }
    $store = Join-Path $env:USERPROFILE '.android\hive-release.jks'
    if (Test-Path $store) { throw "$store already exists, so it was not replaced." }
    New-Item -ItemType Directory -Force (Split-Path $store), (Split-Path $propertiesFile) | Out-Null

    $alias = 'hive'
    $bytes = New-Object byte[] 24
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($bytes)
    $random.Dispose()
    $password = ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''

    Write-Step 'Making the signing key'
    # keytool reads the password from the environment, so that it is not on a command line.
    $env:HIVE_NEW_KEY_PASSWORD = $password
    try {
        Invoke-Program $keytool @(
            '-genkeypair', '-keystore', $store, '-storetype', 'PKCS12', '-alias', $alias,
            '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000', '-dname', 'CN=Hive',
            '-storepass:env', 'HIVE_NEW_KEY_PASSWORD', '-keypass:env', 'HIVE_NEW_KEY_PASSWORD'
        ) 'keytool could not make the key.'

        $lines = @(
            '',
            '# Signs Hive releases. Keep this file private.',
            "hive.signing.storeFile=$($store -replace '\\', '/')",
            "hive.signing.storePassword=$password",
            "hive.signing.keyAlias=$alias",
            "hive.signing.keyPassword=$password"
        )
        [System.IO.File]::AppendAllText($propertiesFile, ($lines -join "`r`n") + "`r`n", (New-Object System.Text.UTF8Encoding $false))

        $listing = @(Read-Program $keytool @('-list', '-v', '-keystore', $store, '-alias', $alias, '-storepass:env', 'HIVE_NEW_KEY_PASSWORD'))
    } finally {
        Remove-Item Env:HIVE_NEW_KEY_PASSWORD -ErrorAction SilentlyContinue
    }
    $sha1 = $listing | Where-Object { $_ -match '([0-9A-F]{2}(:[0-9A-F]{2}){19})\s*$' } | Select-Object -First 1
    Write-Host ''
    Write-Host "Key:      $store"
    Write-Host "Settings: $propertiesFile"
    if ($sha1 -match '([0-9A-F]{2}(:[0-9A-F]{2}){19})\s*$') {
        Write-Host "SHA-1:    $($Matches[1])" -ForegroundColor Green
        Write-Host 'Google sign-in needs this SHA-1 in an Android OAuth client for releases.'
    }
    Write-Host ''
    Write-Host 'Back up both files in a private place. Without them, no release can update an installed Hive.' -ForegroundColor Yellow
}

function Get-Version([string]$Text) {
    if ($Text -notmatch '^v?(0|[1-9]\d*)\.(0|[1-9]\d?)\.(0|[1-9]\d?)$') { return $null }
    return [pscustomobject]@{
        Name = "$($Matches[1]).$($Matches[2]).$($Matches[3])"
        Code = [int]$Matches[1] * 10000 + [int]$Matches[2] * 100 + [int]$Matches[3]
    }
}

function Test-SigningKey {
    $properties = Read-Properties (Get-UserGradleProperties)
    foreach ($name in $SigningNames) {
        if (-not $properties[$name]) { throw 'The signing key is not set up. Run .\run app release --setup-key one time.' }
    }
    if (-not (Test-Path $properties['hive.signing.storeFile'])) {
        throw "The signing key is missing: $($properties['hive.signing.storeFile']). Restore it from your backup."
    }
}

function New-Release([string]$Text) {
    $version = Get-Version $Text
    if (-not $version) { throw "$Text is not a version such as 0.1.0. The second and third numbers go up to 99." }
    if ($version.Code -lt 1) { throw 'The version must be above 0.0.0.' }
    $tag = "v$($version.Name)"

    Write-Step 'Checking'
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI (gh) was not found. Install it from https://cli.github.com.' }
    if (-not (Test-Program 'gh' @('auth', 'status'))) { throw 'GitHub CLI is not signed in. Run gh auth login.' }
    Test-SigningKey

    $branch = @(Read-Program 'git' @('branch', '--show-current'))[0]
    if ($branch -ne 'main') { throw "Releases are made from main, and the current branch is '$branch'." }
    if (@(Read-Program 'git' @('status', '--porcelain')).Where({ $_ }).Count -gt 0) { throw 'There are changes that are not committed.' }
    Invoke-Program 'git' @('fetch', '--quiet', '--tags', 'origin', 'main') 'Could not fetch from GitHub.'
    $head = @(Read-Program 'git' @('rev-parse', 'HEAD'))[0]
    $remote = @(Read-Program 'git' @('rev-parse', 'FETCH_HEAD'))[0]
    if ($head -ne $remote) { throw 'main and origin/main are different. Pull or push first.' }

    $versions = @(Read-Program 'git' @('tag', '--list', 'v*')) | ForEach-Object { Get-Version $_ } | Where-Object { $_ }
    $last = $versions | Sort-Object Code | Select-Object -Last 1
    if ($last -and $version.Code -le $last.Code) { throw "$($version.Name) is not above the last release, $($last.Name)." }

    Write-Step 'Running the tests and building the signed app'
    Invoke-Gradle @('test', ':client:assembleRelease', "-Phive.versionName=$($version.Name)", "-Phive.versionCode=$($version.Code)")
    $built = Join-Path $Root 'client\build\outputs\apk\release\client-release.apk'
    if (-not (Test-Path $built)) { throw "Gradle finished, but there is no signed app at $built" }
    $folder = Join-Path $Root 'build\release'
    New-Item -ItemType Directory -Force $folder | Out-Null
    $apk = Join-Path $folder "hive-$($version.Name).apk"
    Copy-Item $built $apk -Force

    # The notes that someone wrote, or one line for each merge on main since the last release.
    $written = Join-Path $Root "releases\$($version.Name).md"
    $notesFile = Join-Path $folder "notes-$($version.Name).md"
    $range = if ($last) { "v$($last.Name)..HEAD" } else { 'HEAD' }
    $subjects = @(Read-Program 'git' @('log', '--first-parent', '--format=%s', $range))
    $notes = @($subjects | Where-Object { $_ } | ForEach-Object { '- ' + ($_ -replace '\[merge\] ', '') })
    if (Test-Path $written) {
        $notesFile = $written
    } else {
        [System.IO.File]::WriteAllLines($notesFile, [string[]]$notes, (New-Object System.Text.UTF8Encoding $false))
    }

    Write-Step "Ready to publish Hive $($version.Name)"
    Write-Host "Commit:       $($head.Substring(0, 7)) on main"
    Write-Host "Tag:          $tag"
    Write-Host "Version code: $($version.Code)"
    Write-Host "Last release: $(if ($last) { $last.Name } else { 'none' })"
    Write-Host "App:          $apk ($([math]::Round((Get-Item $apk).Length / 1MB, 1)) MB)"
    Write-Host "Notes:        $notesFile"
    if ($notesFile -ne $written) { $notes | ForEach-Object { Write-Host "  $_" } }
    Write-Host ''
    $answer = Read-Host 'Push the tag and publish this release on GitHub? (y/n)'
    if ($answer -notmatch '^(y|yes)$') {
        Write-Host 'Nothing was published.'
        return
    }

    Invoke-Program 'git' @('tag', '-a', $tag, '-m', "Hive $($version.Name)") 'Could not create the tag.'
    try {
        Invoke-Program 'git' @('push', 'origin', "refs/tags/$tag") 'Could not push the tag.'
    } catch {
        Invoke-Program 'git' @('tag', '-d', $tag) 'Could not remove the local tag.'
        throw
    }
    Invoke-Program 'gh' @('release', 'create', $tag, $apk, '--verify-tag', '--title', "Hive $($version.Name)", '--notes-file', $notesFile) `
        "The tag $tag is on GitHub, but the release is not. Try: gh release create $tag `"$apk`" --title `"Hive $($version.Name)`" --notes-file `"$notesFile`""
    Write-Host ''
    Write-Host "Published Hive $($version.Name)." -ForegroundColor Green
}

Invoke-Script {
    if (-not $command) { throw 'Give a version, for example: .\run app release 0.1.0. Run .\run app release --setup-key one time first.' }
    if ($command -eq '--setup-key') { New-SigningKey } else { New-Release $command }
}
