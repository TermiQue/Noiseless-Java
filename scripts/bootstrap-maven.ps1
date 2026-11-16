$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $PSScriptRoot
$version = '3.9.11'
$cache = Join-Path $base '.cache'
$maven = Join-Path $cache "apache-maven-$version"
if (Test-Path (Join-Path $maven 'bin\mvn.cmd')) { exit 0 }

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$staging = Join-Path $cache ('.maven-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging | Out-Null
try {
    $url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.zip"
    $archive = Join-Path $staging 'maven.zip'
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $archive
    $checksum = Join-Path $staging 'maven.sha512'
    Invoke-WebRequest -UseBasicParsing -Uri "$url.sha512" -OutFile $checksum
    $expected = ((Get-Content -LiteralPath $checksum -Raw).Trim() -split '\s+')[0]
    if ($expected -notmatch '^[0-9a-fA-F]{128}$' -or (Get-FileHash -Algorithm SHA512 $archive).Hash -ne $expected) {
        throw 'Maven SHA-512 checksum failed.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $staging
    Move-Item -LiteralPath (Join-Path $staging "apache-maven-$version") -Destination $maven
} finally {
    Remove-Item -LiteralPath $staging -Recurse -Force
}
