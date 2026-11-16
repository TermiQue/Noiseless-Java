# Integration check for the packaged runtime; uses disposable accounts and data only.
$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $PSScriptRoot
$app = Join-Path $base 'target\native\Noiseless'
$java = Join-Path $app 'runtime\bin\java.exe'
$jar = Join-Path $app 'app\noiseless.jar'
$report = Join-Path $base 'target\windows-verification'
New-Item -ItemType Directory -Force -Path $report | Out-Null
$temporary = Join-Path $env:TEMP ('Noiseless test ' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
$server = $null; $mock = $null; $desktop = $null
$cfg = Join-Path $app 'app\Noiseless.cfg'
$originalCfg = [IO.File]::ReadAllBytes($cfg)
function Free-Port {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
function Start-Backend {
    $process = Start-Process -FilePath $java -ArgumentList @('--enable-native-access=ALL-UNNAMED', '-jar', "`"$jar`"", 'server') `
        -PassThru -RedirectStandardOutput (Join-Path $report 'server.log') -RedirectStandardError (Join-Path $report 'server-error.log')
    return $process
}
function Stop-TestProcess($process) {
    if ($null -ne $process -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
}
try {
    $env:PYTHONUTF8 = '1'
    $env:NOISELESS_HOST = '127.0.0.1'
    $env:NOISELESS_PORT = [string](Free-Port)
    $env:MOCK_PORT = [string](Free-Port)
    $env:NOISELESS_DATA_DIR = Join-Path $temporary 'server data'
    $env:NOISELESS_AI_BASE_URL = "http://127.0.0.1:$($env:MOCK_PORT)/v1"
    $env:NOISELESS_AI_PROTOCOL = 'chat'
    $env:NOISELESS_AI_API_KEY = 'mock-testing-only'
    $env:NOISELESS_AI_MODELS = 'mock-noiseless'
    $mock = Start-Process -FilePath (Get-Command python.exe).Source -ArgumentList @("`"$(Join-Path $base 'scripts\mock_ai.py')`"") `
        -PassThru -RedirectStandardOutput (Join-Path $report 'mock.log') -RedirectStandardError (Join-Path $report 'mock-error.log')
    $server = Start-Backend
    $url = "http://127.0.0.1:$($env:NOISELESS_PORT)"
    $state = Join-Path $temporary 'smoke-account.json'
    & python (Join-Path $base 'scripts\remote_smoke.py') --url $url --state $state |
        Set-Content -Encoding UTF8 -Path (Join-Path $report 'server.json')
    if ($LASTEXITCODE -ne 0) { throw 'Packaged backend integration checks failed.' }
    Stop-TestProcess $server
    $server = Start-Backend
    & python (Join-Path $base 'scripts\remote_smoke.py') --url $url --state $state --resume |
        Set-Content -Encoding UTF8 -Path (Join-Path $report 'restart.json')
    if ($LASTEXITCODE -ne 0) { throw 'Packaged backend restart checks failed.' }
    Stop-TestProcess $server

    # Inject a temporary home for the native launcher, then restore its shipped config.
    $desktopHome = Join-Path $temporary 'desktop home'
    New-Item -ItemType Directory -Path $desktopHome | Out-Null
    [IO.File]::AppendAllText($cfg, "`r`njava-options=-Duser.home=`"$desktopHome`"`r`n", (New-Object Text.UTF8Encoding($false)))
    $desktop = Start-Process -FilePath (Join-Path $app 'Noiseless.exe') -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        $desktop.Refresh()
        if ($desktop.HasExited) { throw "Native desktop exited early: $($desktop.ExitCode)" }
        if ($desktop.MainWindowHandle -ne 0 -and $desktop.MainWindowTitle -like '*Noiseless*' -and
            (Test-Path (Join-Path $desktopHome '.noiseless-java\local-account.json'))) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Native desktop window or isolated data did not initialize.' }
    @{ nativeLauncher = $true; visibleWindow = $true; isolatedHome = $true; realAiCalls = 0 } |
        ConvertTo-Json | Set-Content -Encoding UTF8 -Path (Join-Path $report 'desktop.json')
    Write-Output 'Packaged Windows backend, persistence and native desktop startup passed.'
} finally {
    Stop-TestProcess $desktop
    Stop-TestProcess $server
    Stop-TestProcess $mock
    [IO.File]::WriteAllBytes($cfg, $originalCfg)
    Remove-Item -LiteralPath $temporary -Recurse -Force
}
