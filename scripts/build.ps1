$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
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
New-Item -ItemType Directory -Force (Join-Path $root 'backend/src/main/resources/static') | Out-Null
Copy-Item (Join-Path $root 'web/dist/*') (Join-Path $root 'backend/src/main/resources/static') -Recurse -Force
Run 'backend' { .\mvnw.cmd verify }
Run 'extension' { npm.cmd ci }
foreach ($task in @('format:check', 'lint', 'typecheck', 'build')) {
    Run 'extension' { npm.cmd run $task }
}
Run 'extension' { npm.cmd run test -- --run }
New-Item -ItemType Directory -Force (Join-Path $root 'dist') | Out-Null
Run 'extension' { npm.cmd run package }
Copy-Item (Join-Path $root 'backend/target/cheapreview-0.1.0.jar') (Join-Path $root 'dist/cheapreview-0.1.0.jar') -Force
