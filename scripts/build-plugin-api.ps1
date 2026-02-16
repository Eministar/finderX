$ErrorActionPreference = "Stop"

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    try {
        & "scripts/sync-plugin-api.ps1"
    } catch {
        throw "Plugin API source sync failed: $($_.Exception.Message)"
    }

    mvn -q -f "finderx-plugin-api/pom.xml" -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE"
    }

    New-Item -ItemType Directory -Force -Path "dist" | Out-Null
    $outJar = "dist/FinderX-Plugin-API.jar"
    if (Test-Path $outJar) {
        Remove-Item $outJar -Force
    }

    $builtJar = Get-ChildItem -Path "finderx-plugin-api/target" -Filter "finderx-plugin-api-*.jar" -File |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $builtJar) {
        throw "Could not find built API jar in finderx-plugin-api/target"
    }

    Copy-Item -Path $builtJar.FullName -Destination $outJar -Force
    Write-Host "Created $outJar"
} finally {
    Pop-Location
}
