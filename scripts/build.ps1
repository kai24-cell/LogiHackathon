$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
function Clear-GeneratedDirectory([string]$relativePath) {
    # 上書きだけでは旧assetや削除済みTypeScript出力が配布物へ残るため初期化する。
    if ($relativePath -notin @('backend/src/main/resources/static', 'extension/dist')) {
        throw '生成専用ディレクトリ以外は初期化できません。'
    }
    $workspacePath = [System.IO.Path]::GetFullPath($root).TrimEnd('\')
    $targetPath = [System.IO.Path]::GetFullPath((Join-Path $root $relativePath))
    if (!$targetPath.StartsWith($workspacePath + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw '初期化対象が作業フォルダ外です。'
    }
    # 親や内部にjunction/symlinkがある場合は、別フォルダへ削除が及ばないよう中止する。
    $ancestor = $targetPath
    while ($ancestor.Length -ge $workspacePath.Length) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
                throw 'リンクを含む生成フォルダは初期化できません。'
            }
        }
        if ($ancestor -eq $workspacePath) { break }
        $ancestor = Split-Path $ancestor -Parent
    }
    if (Test-Path -LiteralPath $targetPath) {
        $pending = New-Object 'System.Collections.Generic.Stack[string]'
        $pending.Push($targetPath)
        while ($pending.Count -gt 0) {
            foreach ($child in (Get-ChildItem -LiteralPath $pending.Pop() -Force)) {
                if ($child.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
                    throw 'リンクを含む生成フォルダは初期化できません。'
                }
                if ($child.PSIsContainer) { $pending.Push($child.FullName) }
            }
        }
        Remove-Item -LiteralPath $targetPath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $targetPath -Force | Out-Null
}
function Run([string]$directory, [scriptblock]$command) {
    Push-Location (Join-Path $root $directory)
    try {
        & $command
        if ($LASTEXITCODE -ne 0) {
            throw "Build failed: $directory"
        }
    } finally {
        Pop-Location
    }
}
Run 'web' { npm.cmd ci }
foreach ($task in @('format:check', 'lint', 'typecheck')) {
    Run 'web' { npm.cmd run $task }
}
Run 'web' { npm.cmd run test -- --run }
Run 'web' { npm.cmd run build }
Clear-GeneratedDirectory 'backend/src/main/resources/static'
Copy-Item (Join-Path $root 'web/dist/*') (Join-Path $root 'backend/src/main/resources/static') -Recurse -Force
Run 'backend' { .\mvnw.cmd clean verify }
Run 'extension' { npm.cmd ci }
Clear-GeneratedDirectory 'extension/dist'
foreach ($task in @('format:check', 'lint', 'typecheck', 'build')) {
    Run 'extension' { npm.cmd run $task }
}
Run 'extension' { npm.cmd run test -- --run }
New-Item -ItemType Directory -Force (Join-Path $root 'dist') | Out-Null
Run 'extension' { npm.cmd run package }
Copy-Item (Join-Path $root 'backend/target/cheapreview-0.1.0.jar') (Join-Path $root 'dist/cheapreview-0.1.0.jar') -Force
