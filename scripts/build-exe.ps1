param(
    [string]$AppName = "FinderX",
    [string]$Vendor = "Eministar",
    [switch]$NoSign,
    [string]$CertPfxPath = $env:CODE_SIGN_PFX,
    [string]$CertPfxPassword = $env:CODE_SIGN_PFX_PASSWORD,
    [string]$CertThumbprint = $env:CODE_SIGN_CERT_SHA1,
    [string]$TimestampUrl = $(if ($env:CODE_SIGN_TIMESTAMP_URL) { $env:CODE_SIGN_TIMESTAMP_URL } else { "http://timestamp.digicert.com" }),
    [string]$SignToolPath = $env:SIGNTOOL_PATH
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Sign = -not $NoSign
$script:SignTool = $null
$script:SigningBackend = $null

function Test-SigningConfigPresent {
    if ($CertPfxPath -and -not [string]::IsNullOrWhiteSpace($CertPfxPassword)) {
        return $true
    }
    if ($CertThumbprint -and -not [string]::IsNullOrWhiteSpace($CertThumbprint)) {
        return $true
    }
    return $false
}

function Write-Step {
    param([string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Write-Info {
    param([string]$Message)
    Write-Host "    $Message" -ForegroundColor DarkGray
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Fail {
    param([string]$Message)
    throw $Message
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
        Fail "Command failed: $Exe $($CommandArgs -join ' ')"
    }
}

function Get-ProjectVersionFromPom {
    $pomPath = "pom.xml"
    if (!(Test-Path $pomPath)) {
        Fail "pom.xml nicht gefunden."
    }

    [xml]$pom = Get-Content -Raw -Path $pomPath
    $ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $ns.AddNamespace("m", "http://maven.apache.org/POM/4.0.0")

    $versionNode = $pom.SelectSingleNode("/m:project/m:version", $ns)
    if ($versionNode -eq $null -or [string]::IsNullOrWhiteSpace($versionNode.InnerText)) {
        $versionNode = $pom.SelectSingleNode("/m:project/m:parent/m:version", $ns)
    }

    if ($versionNode -eq $null -or [string]::IsNullOrWhiteSpace($versionNode.InnerText)) {
        Fail "Konnte Projektversion nicht aus pom.xml lesen."
    }

    $rawVersion = $versionNode.InnerText.Trim()
    if ($rawVersion -match '^\$\{(.+)\}$') {
        $propName = $matches[1]
        $propNode = $pom.SelectSingleNode("/m:project/m:properties/*[local-name()='$propName']", $ns)
        if ($propNode -eq $null -or [string]::IsNullOrWhiteSpace($propNode.InnerText)) {
            Fail "Version referenziert Property '$propName', aber sie wurde nicht gefunden."
        }
        $rawVersion = $propNode.InnerText.Trim()
    }

    return $rawVersion
}

function Convert-ToJPackageVersion {
    param([string]$RawVersion)

    $parts = [System.Text.RegularExpressions.Regex]::Matches($RawVersion, '\d+') | ForEach-Object { $_.Value }
    if ($parts.Count -eq 0) {
        return "1.0.0"
    }

    $normalized = @()
    for ($i = 0; $i -lt [Math]::Min(3, $parts.Count); $i++) {
        $normalized += $parts[$i]
    }

    while ($normalized.Count -lt 3) {
        $normalized += "0"
    }

    return ($normalized -join ".")
}

function Resolve-InnoCompiler {
    if ($env:INNO_SETUP_COMPILER -and (Test-Path $env:INNO_SETUP_COMPILER)) {
        return $env:INNO_SETUP_COMPILER
    }

    $candidates = @(
        "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
        "C:\Program Files\Inno Setup 6\ISCC.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    Fail "Inno Setup Compiler (ISCC.exe) nicht gefunden. Installiere Inno Setup 6 oder setze INNO_SETUP_COMPILER."
}

function Resolve-MainJar {
    param([string]$ArtifactPrefix)

    $jar = Get-ChildItem -Path "target" -Filter "$ArtifactPrefix-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" -and $_.Name -notlike "original-*" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $jar) {
        Fail "Kein ausführbares JAR in target gefunden."
    }

    return $jar.Name
}

function Ensure-InstallerArt {
    $assetsDir = "installer\assets"
    $wizardBmp = Join-Path $assetsDir "wizard.bmp"
    $smallBmp = Join-Path $assetsDir "wizard-small.bmp"

    if ((Test-Path $wizardBmp) -and (Test-Path $smallBmp)) {
        return
    }

    New-Item -ItemType Directory -Path $assetsDir -Force | Out-Null

    try {
        Add-Type -AssemblyName System.Drawing -ErrorAction Stop
    }
    catch {
        Write-Info "System.Drawing nicht verfügbar, Installer-Artwork wird übersprungen."
        return
    }

    $logoPath = "src\main\resources\icons\app-logo.png"
    $logo = $null
    if (Test-Path $logoPath) {
        $logo = [System.Drawing.Image]::FromFile((Resolve-Path $logoPath))
    }

    try {
        $bmp = New-Object System.Drawing.Bitmap 164, 314
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

        $rect = New-Object System.Drawing.Rectangle 0, 0, 164, 314
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, ([System.Drawing.Color]::FromArgb(20,20,20)), ([System.Drawing.Color]::FromArgb(10,10,10)), 90.0
        $g.FillRectangle($brush, $rect)

        $accent = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(32, 240, 240, 240))
        $g.FillRectangle($accent, 0, 0, 164, 6)

        if ($logo -ne $null) {
            $logoSize = 72
            $x = [int](($bmp.Width - $logoSize) / 2)
            $y = 44
            $g.DrawImage($logo, $x, $y, $logoSize, $logoSize)
        }

        $fontTitle = New-Object System.Drawing.Font("Segoe UI", 16, [System.Drawing.FontStyle]::Bold)
        $fontSub = New-Object System.Drawing.Font("Segoe UI", 9, [System.Drawing.FontStyle]::Regular)
        $titleBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(245,245,245))
        $subBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(170,170,170))
        $sf = New-Object System.Drawing.StringFormat
        $sf.Alignment = [System.Drawing.StringAlignment]::Center

        $g.DrawString($AppName, $fontTitle, $titleBrush, 82, 140, $sf)
        $g.DrawString("Fast local search", $fontSub, $subBrush, 82, 168, $sf)
        $g.DrawString("for Windows", $fontSub, $subBrush, 82, 184, $sf)

        $bmp.Save((Join-Path $assetsDir "wizard.bmp"), [System.Drawing.Imaging.ImageFormat]::Bmp)

        $small = New-Object System.Drawing.Bitmap 55, 55
        $gs = [System.Drawing.Graphics]::FromImage($small)
        $gs.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $gs.Clear([System.Drawing.Color]::FromArgb(20,20,20))
        if ($logo -ne $null) {
            $gs.DrawImage($logo, 7, 7, 41, 41)
        }
        $small.Save((Join-Path $assetsDir "wizard-small.bmp"), [System.Drawing.Imaging.ImageFormat]::Bmp)

        $gs.Dispose()
        $small.Dispose()
        $g.Dispose()
        $bmp.Dispose()
        $brush.Dispose()
        $accent.Dispose()
        $fontTitle.Dispose()
        $fontSub.Dispose()
        $titleBrush.Dispose()
        $subBrush.Dispose()
        $sf.Dispose()
    }
    finally {
        if ($logo -ne $null) {
            $logo.Dispose()
        }
    }
}

function Resolve-SignTool {
    param([string]$RequestedPath)

    if ($RequestedPath -and (Test-Path $RequestedPath)) {
        return $RequestedPath
    }

    $cmd = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    $sdkCandidates = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" -Recurse -Filter "signtool.exe" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*\x64\signtool.exe" } |
        Sort-Object FullName -Descending

    if ($sdkCandidates -and $sdkCandidates.Count -gt 0) {
        return $sdkCandidates[0].FullName
    }

    return $null
}

function Get-SigningCertificate {
    if ($CertPfxPath) {
        if (!(Test-Path $CertPfxPath)) {
            Fail "PFX-Datei nicht gefunden: $CertPfxPath"
        }
        if ([string]::IsNullOrWhiteSpace($CertPfxPassword)) {
            Fail "PFX Passwort fehlt (CODE_SIGN_PFX_PASSWORD)."
        }

        $secure = ConvertTo-SecureString $CertPfxPassword -AsPlainText -Force
        return New-Object System.Security.Cryptography.X509Certificates.X509Certificate2(
            (Resolve-Path $CertPfxPath),
            $secure,
            [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::Exportable
        )
    }

    if ($CertThumbprint) {
        $thumb = ($CertThumbprint -replace '\s', '').ToUpperInvariant()
        $cert = Get-ChildItem Cert:\CurrentUser\My, Cert:\LocalMachine\My -ErrorAction SilentlyContinue |
            Where-Object { $_.Thumbprint -eq $thumb } |
            Select-Object -First 1
        if ($null -eq $cert) {
            Fail "Zertifikat mit Thumbprint $CertThumbprint nicht in Cert:\\CurrentUser\\My oder Cert:\\LocalMachine\\My gefunden."
        }
        return $cert
    }

    Fail "Keine Signing-Konfiguration gefunden. Setze CODE_SIGN_PFX + CODE_SIGN_PFX_PASSWORD oder CODE_SIGN_CERT_SHA1."
}

function Resolve-SigningBackend {
    if ($script:SigningBackend) {
        return $script:SigningBackend
    }

    $tool = Resolve-SignTool -RequestedPath $SignToolPath
    if ($tool) {
        $script:SignTool = $tool
        $script:SigningBackend = "signtool"
        Write-Info "Signing backend: signtool ($tool)"
        return $script:SigningBackend
    }

    if (Get-Command Set-AuthenticodeSignature -ErrorAction SilentlyContinue) {
        $script:SigningBackend = "authenticode"
        Write-Info "Signing backend: Set-AuthenticodeSignature (fallback)"
        return $script:SigningBackend
    }

    Fail "Weder signtool.exe noch Set-AuthenticodeSignature verfügbar. Installiere Windows SDK oder nutze PowerShell 5.1+."
}

function Sign-Executable {
    param([string]$FilePath)

    if (!(Test-Path $FilePath)) {
        Fail "Zu signierende Datei nicht gefunden: $FilePath"
    }

    $backend = Resolve-SigningBackend
    if ($backend -eq "signtool") {
        $args = @("sign", "/fd", "SHA256", "/tr", $TimestampUrl, "/td", "SHA256", "/d", $AppName)
        if ($CertPfxPath) {
            $args += @("/f", $CertPfxPath, "/p", $CertPfxPassword)
        } elseif ($CertThumbprint -and -not [string]::IsNullOrWhiteSpace($CertThumbprint)) {
            $args += @("/sha1", $CertThumbprint, "/s", "MY")
        } else {
            Fail "Signing aktiviert, aber kein Zertifikat gesetzt. Setze CODE_SIGN_PFX + CODE_SIGN_PFX_PASSWORD oder CODE_SIGN_CERT_SHA1."
        }
        $args += $FilePath
        Invoke-Checked -Exe $script:SignTool -CommandArgs $args
        return
    }

    $cert = Get-SigningCertificate
    $result = Set-AuthenticodeSignature -FilePath $FilePath -Certificate $cert -TimestampServer $TimestampUrl -HashAlgorithm SHA256
    if ($result.Status -ne "Valid") {
        Fail "Authenticode signing fehlgeschlagen: $FilePath (Status: $($result.Status))"
    }
}

$start = Get-Date

$projectVersionRaw = Get-ProjectVersionFromPom
$jpackageVersion = Convert-ToJPackageVersion -RawVersion $projectVersionRaw

if ($Sign -and -not (Test-SigningConfigPresent)) {
    Write-Info "Kein Zertifikat konfiguriert -> Signierung wird automatisch deaktiviert."
    $Sign = $false
}

Write-Step "Build metadata"
Write-Info "Version (pom): $projectVersionRaw"
Write-Info "Version (jpackage): $jpackageVersion"
Write-Info "Signing: $(if ($Sign) { 'enabled' } else { 'disabled' })"

Write-Step "Prepare installer artwork"
Ensure-InstallerArt
Write-Ok "Installer artwork ready"

Write-Step "Build JAR"
Invoke-Checked -Exe "mvn" -CommandArgs @("-DskipTests", "clean", "package")
Write-Ok "JAR built"

Write-Step "Prepare jpackage input"
$jpackageInputDir = "target\jpackage-input"
if (Test-Path $jpackageInputDir) { Remove-Item $jpackageInputDir -Recurse -Force }
New-Item -ItemType Directory -Path $jpackageInputDir | Out-Null

Invoke-Checked -Exe "mvn" -CommandArgs @(
    "-DskipTests",
    "dependency:copy-dependencies",
    "-DincludeScope=runtime",
    "-DoutputDirectory=target/jpackage-input",
    "-DexcludeTransitive=false"
)
Write-Ok "Runtime dependencies copied"

Write-Step "Prepare dist"
$apiJarPath = "dist\FinderX-Plugin-API.jar"
$apiJarBackup = $null
if (Test-Path $apiJarPath) {
    $apiJarBackup = Join-Path ([System.IO.Path]::GetTempPath()) ("FinderX-Plugin-API-" + [guid]::NewGuid().ToString("N") + ".jar")
    Copy-Item -Path $apiJarPath -Destination $apiJarBackup -Force
    Write-Info "Preserved existing API JAR before dist cleanup."
}
if (Test-Path dist) { Remove-Item dist -Recurse -Force }
New-Item -ItemType Directory dist | Out-Null
if ($apiJarBackup -and (Test-Path $apiJarBackup)) {
    Copy-Item -Path $apiJarBackup -Destination $apiJarPath -Force
    Remove-Item $apiJarBackup -Force -ErrorAction SilentlyContinue
    Write-Info "Restored preserved API JAR into dist."
}
Write-Ok "dist prepared"

Write-Step "Build app image"
$mainJar = Resolve-MainJar -ArtifactPrefix $AppName
$mainJarPath = Join-Path "target" $mainJar
Copy-Item -Path $mainJarPath -Destination (Join-Path $jpackageInputDir $mainJar) -Force

$iconPath = "src/main/resources/icons/app.ico"
$appImageArgs = @(
  "--type", "app-image",
  "--name", $AppName,
  "--input", $jpackageInputDir,
  "--main-jar", $mainJar,
  "--main-class", "dev.eministar.Main",
  "--dest", "dist",
  "--vendor", $Vendor,
  "--app-version", $jpackageVersion
)
if (Test-Path $iconPath) { $appImageArgs += @( "--icon", $iconPath ) }
Invoke-Checked -Exe "jpackage" -CommandArgs $appImageArgs

$appExe = "dist\\FinderX\\FinderX.exe"
if (!(Test-Path $appExe)) {
    Fail "App image nicht korrekt erzeugt (dist\\FinderX\\FinderX.exe fehlt)."
}
Write-Ok "App image ready"

if ($Sign) {
    Write-Step "Sign app executable"
    Sign-Executable -FilePath $appExe
    Write-Ok "App EXE signed"
}

Write-Step "Build Inno installer"
$iscc = Resolve-InnoCompiler
$appImageDirAbs = (Resolve-Path "dist\\FinderX").Path
Invoke-Checked -Exe $iscc -CommandArgs @("/DMyAppVersion=$projectVersionRaw", "/DAppImageDir=$appImageDirAbs", "installer\\FinderX.iss")

$installerCandidates = @(
    "dist\\$AppName-Setup-$projectVersionRaw.exe",
    "installer\\dist\\$AppName-Setup-$projectVersionRaw.exe"
)
$installerPath = $installerCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($installerPath)) {
    Fail "Installer nicht gefunden. Erwartet: $($installerCandidates -join ', ')"
}
Write-Ok "Installer built"

if ($Sign) {
    Write-Step "Sign installer"
    Sign-Executable -FilePath $installerPath
    Write-Ok "Installer signed"
}

$duration = [Math]::Round(((Get-Date) - $start).TotalSeconds, 1)
Write-Host "`nDone in $duration s" -ForegroundColor Green
Write-Host "Installer: $installerPath" -ForegroundColor Yellow
