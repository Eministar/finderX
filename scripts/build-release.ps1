param(
    [switch]$NoSign,
    [switch]$SkipInstaller,
    [switch]$SkipApi,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $start = Get-Date

    function Write-Section {
        param([string]$Title)
        Write-Host ""
        Write-Host ("=" * 72) -ForegroundColor DarkCyan
        Write-Host ("  {0}" -f $Title) -ForegroundColor Cyan
        Write-Host ("=" * 72) -ForegroundColor DarkCyan
    }

    function Write-Step {
        param([string]$Text)
        Write-Host (" -> {0}" -f $Text) -ForegroundColor Gray
    }

    function Invoke-Checked {
        param(
            [Parameter(Mandatory = $true)]
            [string]$Exe,
            [Parameter(Mandatory = $false)]
            [string[]]$CommandArgs = @()
        )
        & $Exe @CommandArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed: $Exe $($CommandArgs -join ' ')"
        }
    }

    function Get-Version {
        [xml]$pom = Get-Content -Raw -Path "pom.xml"
        $ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
        $ns.AddNamespace("m", "http://maven.apache.org/POM/4.0.0")
        $versionNode = $pom.SelectSingleNode("/m:project/m:version", $ns)
        if ($versionNode -eq $null -or [string]::IsNullOrWhiteSpace($versionNode.InnerText)) {
            throw "Could not resolve version from pom.xml"
        }
        return $versionNode.InnerText.Trim()
    }

    $projectVersion = Get-Version

    Write-Section "FinderX Release Pipeline"
    Write-Host ("Project Version : {0}" -f $projectVersion) -ForegroundColor Yellow
    Write-Host ("Signing         : {0}" -f ($(if ($NoSign) { "disabled" } else { "enabled (via build-exe.ps1 config)" }))) -ForegroundColor Yellow
    Write-Host ("Build API       : {0}" -f ($(if ($SkipApi) { "no" } else { "yes" }))) -ForegroundColor Yellow
    Write-Host ("Build Installer : {0}" -f ($(if ($SkipInstaller) { "no" } else { "yes" }))) -ForegroundColor Yellow

    if (-not $SkipApi) {
        Write-Section "Step 1 - Build Plugin API"
        Write-Step "Running scripts/build-plugin-api.ps1"
        Invoke-Checked -Exe "pwsh" -CommandArgs @("-File", "scripts/build-plugin-api.ps1")
        if (-not (Test-Path "dist/FinderX-Plugin-API.jar")) {
            throw "Expected artifact missing: dist/FinderX-Plugin-API.jar"
        }
        Write-Host " API JAR ready: dist/FinderX-Plugin-API.jar" -ForegroundColor Green
    }

    if (-not $SkipInstaller) {
        $apiJarBackup = $null
        if (-not $SkipApi -and (Test-Path "dist/FinderX-Plugin-API.jar")) {
            $apiJarBackup = Join-Path ([System.IO.Path]::GetTempPath()) ("FinderX-Plugin-API-" + [guid]::NewGuid().ToString("N") + ".jar")
            Copy-Item -Path "dist/FinderX-Plugin-API.jar" -Destination $apiJarBackup -Force
            Write-Step "Backed up API JAR before installer build"
        }

        Write-Section "Step 2 - Build App + Installer"
        $args = @("-File", "scripts/build-exe.ps1")
        if ($NoSign) {
            $args += "-NoSign"
        }
        if ($SkipTests) {
            Write-Step "Skip tests requested (handled via build-exe/maven flags)"
        }
        Write-Step ("Running pwsh {0}" -f ($args -join " "))
        Invoke-Checked -Exe "pwsh" -CommandArgs $args

        if ($apiJarBackup -and (Test-Path $apiJarBackup)) {
            New-Item -ItemType Directory -Force -Path "dist" | Out-Null
            Copy-Item -Path $apiJarBackup -Destination "dist/FinderX-Plugin-API.jar" -Force
            Remove-Item $apiJarBackup -Force -ErrorAction SilentlyContinue
            Write-Step "Restored API JAR after installer build"
        }
    }

    Write-Section "Artifacts"
    if (Test-Path "dist") {
        Get-ChildItem -Path "dist" -File | Sort-Object Name | ForEach-Object {
            Write-Host (" - {0} ({1} KB)" -f $_.Name, [Math]::Round($_.Length / 1KB, 1)) -ForegroundColor Gray
        }
    } else {
        Write-Host " - dist folder not found" -ForegroundColor DarkYellow
    }

    $duration = [Math]::Round(((Get-Date) - $start).TotalSeconds, 1)
    Write-Host ""
    Write-Host ("Pipeline completed in {0} s" -f $duration) -ForegroundColor Green
}
finally {
    Pop-Location
}
