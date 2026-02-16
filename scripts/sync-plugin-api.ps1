$ErrorActionPreference = "Stop"

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $sourceDir = "src/main/java/dev/eministar/plugins/api"
    $targetDir = "finderx-plugin-api/src/main/java/dev/eministar/plugins/api"

    if (!(Test-Path $sourceDir)) {
        throw "Source API directory not found: $sourceDir"
    }

    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    Get-ChildItem -Path $targetDir -Filter "*.java" -File -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            Remove-Item -Path $_.FullName -Force -ErrorAction SilentlyContinue
        } catch {
        }
    }
    Copy-Item -Path (Join-Path $sourceDir "*.java") -Destination $targetDir -Force
    Write-Host "Synced Plugin API sources to module."
} finally {
    Pop-Location
}
