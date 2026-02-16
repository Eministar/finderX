$ErrorActionPreference = "Stop"

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    mvn -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) {
        throw "Maven compile failed with exit code $LASTEXITCODE"
    }

    New-Item -ItemType Directory -Force -Path "dist" | Out-Null
    $outJar = "dist/FinderX-Plugin-API.jar"
    if (Test-Path $outJar) {
        Remove-Item $outJar -Force
    }

    jar --create --file $outJar -C "target/classes" "dev/eministar/plugins/api"
    Write-Host "Created $outJar"
} finally {
    Pop-Location
}
