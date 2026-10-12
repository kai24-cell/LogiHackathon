$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    # ソースと成果物を同じcommitに揃える。利用者の未コミット変更は破棄しない。
    $changes = git status --porcelain
    if ($LASTEXITCODE -ne 0) { throw 'Gitの状態を確認できませんでした。' }
    if ($changes) { throw '変更を確認・コミットしてからZIPを作成してください。' }
    $sourceCommit = git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Gitのcommitを確認できませんでした。' }
    & (Join-Path $PSScriptRoot 'build.ps1')
    $afterCommit = git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Gitのcommitを確認できませんでした。' }
    $afterChanges = git status --porcelain
    if ($LASTEXITCODE -ne 0 -or $afterChanges -or $sourceCommit -ne $afterCommit) {
        throw 'ビルド中にソースが変わりました。確認して再実行してください。'
    }
    # 以前のjar/VSIXを最新の証拠にせず、今回のbuild成功後だけ梱包する。
    python (Join-Path $PSScriptRoot 'package_distribution.py')
    if ($LASTEXITCODE -ne 0) { throw 'ZIPの安全検査または作成に失敗しました。' }
    Write-Host 'dist/cheapreview-source.zipを作成・検査しました。実操作の確認は導入ガイドに従ってください。'
} finally { Pop-Location }
