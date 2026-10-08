$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    # Archive tracked source only, so untracked user code and credentials cannot enter.
    $changes = git status --porcelain
    if ($changes) { throw '変更を確認・コミットしてからZIPを作成してください。' }
    New-Item -ItemType Directory -Force dist | Out-Null
    git archive --format=zip --output=dist/cheapreview-source.zip HEAD
    if ($LASTEXITCODE -ne 0) { throw 'git archive failed' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open((Join-Path $root 'dist/cheapreview-source.zip'), 'Update')
    try {
        foreach ($name in @('cheapreview-0.1.0.jar', 'cheapreview-0.1.0.vsix')) {
            $path = Join-Path $root "dist/$name"
            if (!(Test-Path -LiteralPath $path)) { throw '先にbuild.ps1を実行してください。' }
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $path, "dist/$name") | Out-Null
        }
    } finally { $zip.Dispose() }
    Write-Host 'dist/cheapreview-source.zipを作成しました。提出前に内容を確認してください。'
} finally { Pop-Location }
