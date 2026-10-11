param(
    [switch]$Real,
    [switch]$AllowPaidApi,
    [ValidateSet('normal', 'invalid', 'timeout')][string]$Scenario = 'normal',
    [string]$Jar = '',
    [int]$Port = 18766
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$arguments = @((Join-Path $PSScriptRoot 'verify_api.py'), '--scenario', $Scenario, '--port', "$Port")
if ($Jar) { $arguments += @('--jar', $Jar) }
if ($Real) { $arguments += '--real' }
if ($AllowPaidApi) { $arguments += '--allow-paid-api' }
& python @arguments
if ($LASTEXITCODE -ne 0) { throw 'API検証に失敗しました。表示された固定エラーコードを確認してください。自動再試行はしません。' }
