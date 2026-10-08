param([switch]$Dev, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$backendPort = 8765
$developmentPort = 5173
$tokenBytesLength = 32
$startupAttempts = 60
$startupPollMilliseconds = 500
$jar = Join-Path $root 'dist/cheapreview-0.1.0.jar'
if (!(Test-Path -LiteralPath $jar)) { throw '先にscripts/build.ps1を実行してください。' }
$version = & java --version | Out-String
if ($version -notmatch '(?:java|openjdk) 21[.\s]') { throw 'Java 21をJAVA_HOME/PATHに設定してください。' }
$listener = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback), $backendPort
try {
    $listener.Start()
} catch {
    throw "ポート$backendPort が使用中です。起動中のアプリを確認してください。"
} finally {
    $listener.Stop()
}
if (!$env:LOCALAPPDATA) { throw 'LOCALAPPDATAが設定されていません。' }
$runtimeDir = Join-Path $env:LOCALAPPDATA 'CheapReview'
$runtimePath = Join-Path $runtimeDir 'runtime.json'
New-Item -ItemType Directory -Force $runtimeDir | Out-Null
$bytes = New-Object byte[] $tokenBytesLength
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($bytes)
$random.Dispose()
$token = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$previousToken = $env:CHEAPREVIEW_CONNECTION_TOKEN
$previousOrigin = $env:CHEAPREVIEW_DEV_ORIGIN
$process = $null
try {
    $env:CHEAPREVIEW_CONNECTION_TOKEN = $token
    $env:CHEAPREVIEW_DEV_ORIGIN = if ($Dev) { "http://127.0.0.1:$developmentPort" } else { '' }
    @{ port = $backendPort; token = $token; protocolVersion = '1' } |
        ConvertTo-Json | Set-Content -LiteralPath $runtimePath -Encoding UTF8
    $process = Start-Process -FilePath 'java' -ArgumentList @('-jar', ('"' + $jar + '"')) -WindowStyle Hidden -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt $startupAttempts; $attempt++) {
        if ($process.HasExited) { throw 'バックエンドの起動に失敗しました。' }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$backendPort/api/v1/health"
            if ($health.status -eq 'UP') {
                $ready = $true
                break
            }
        } catch {
            # The child process may still be starting; retry until the startup deadline.
        }
        Start-Sleep -Milliseconds $startupPollMilliseconds
    }
    if (!$ready) { throw '起動待機がタイムアウトしました。' }
    Write-Host 'CheapReview起動済み。VS CodeでCheapReview: Open Webを実行してください。終了はCtrl+C。'
    if (!$NoBrowser) {
        $port = if ($Dev) { $developmentPort } else { $backendPort }
        Start-Process "http://127.0.0.1:$port/#connect=$token" -WindowStyle Hidden
    }
    while (!$process.HasExited) {
        Start-Sleep -Milliseconds $startupPollMilliseconds
    }
} finally {
    if ($process -and !$process.HasExited) { Stop-Process -Id $process.Id }
    if (Test-Path -LiteralPath $runtimePath) { Remove-Item -LiteralPath $runtimePath }
    $env:CHEAPREVIEW_CONNECTION_TOKEN = $previousToken
    $env:CHEAPREVIEW_DEV_ORIGIN = $previousOrigin
    $token = $null
}
