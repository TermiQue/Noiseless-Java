param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this script on Windows. Use package-desktop.sh on macOS/Linux.' }
$base = Split-Path -Parent $PSScriptRoot
if (-not $SkipBuild) {
    & (Join-Path $base 'mvnw.cmd') -q clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}
$jar = Join-Path $base 'target\noiseless.jar'
if (-not (Test-Path $jar)) { throw 'Build target\noiseless.jar first.' }
$jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jpackage.exe' } else { (Get-Command jpackage.exe -ErrorAction Stop).Source }
if (-not (Test-Path $jpackage)) { throw 'A full JDK 21+ with jpackage is required.' }
$staging = Join-Path $base ('target\.package-' + [Guid]::NewGuid().ToString('N'))
$native = Join-Path $base 'target\native'
$destination = Join-Path $native 'Noiseless'
$previous = Join-Path $staging 'previous'
try {
    New-Item -ItemType Directory -Path (Join-Path $staging 'input') -Force | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $staging 'input\noiseless.jar')
    [xml]$pom = Get-Content -LiteralPath (Join-Path $base 'pom.xml') -Raw
    & $jpackage --type app-image --name Noiseless --app-version $pom.project.version --vendor Noiseless `
        --input (Join-Path $staging 'input') --main-jar noiseless.jar --main-class io.noiseless.Main `
        --jlink-options '--strip-debug --no-man-pages --no-header-files' `
        --java-options '--enable-native-access=ALL-UNNAMED' --dest (Join-Path $staging 'output')
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
    New-Item -ItemType Directory -Path $native -Force | Out-Null
    if (Test-Path $destination) { Move-Item -LiteralPath $destination -Destination $previous }
    try {
        Move-Item -LiteralPath (Join-Path $staging 'output\Noiseless') -Destination $destination
    } catch {
        if (Test-Path $previous) { Move-Item -LiteralPath $previous -Destination $destination }
        throw
    }
    $zip = Join-Path $native 'Noiseless-windows.zip'
    $stagedZip = Join-Path $staging 'Noiseless-windows.zip'
    Compress-Archive -LiteralPath $destination -DestinationPath $stagedZip
    Move-Item -LiteralPath $stagedZip -Destination $zip -Force
    Write-Output "App: $(Join-Path $destination 'Noiseless.exe')"
    Write-Output "Archive: $zip"
} finally {
    if ((Test-Path $previous) -and -not (Test-Path $destination)) {
        Write-Warning "Previous app retained at $previous after failed rollback."
    } elseif (Test-Path $staging) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
}
