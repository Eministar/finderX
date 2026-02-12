param(
    [string]$Subject = "CN=FinderX Dev Signing",
    [string]$OutDir = "certs",
    [string]$PfxPassword = "dev-password-change-me",
    [int]$YearsValid = 3
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($YearsValid -lt 1) {
    throw "YearsValid muss >= 1 sein."
}

New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

$cert = New-SelfSignedCertificate `
    -Type CodeSigningCert `
    -Subject $Subject `
    -KeyAlgorithm RSA `
    -KeyLength 3072 `
    -HashAlgorithm SHA256 `
    -NotAfter (Get-Date).AddYears($YearsValid) `
    -CertStoreLocation "Cert:\CurrentUser\My"

if ($null -eq $cert) {
    throw "Self-signed Zertifikat konnte nicht erstellt werden."
}

$safeName = ($Subject -replace '[^a-zA-Z0-9\-_\.]', '_')
$pfxPath = Join-Path $OutDir "$safeName.pfx"
$cerPath = Join-Path $OutDir "$safeName.cer"
$passwordSecure = ConvertTo-SecureString $PfxPassword -AsPlainText -Force

Export-PfxCertificate -Cert $cert -FilePath $pfxPath -Password $passwordSecure | Out-Null
Export-Certificate -Cert $cert -FilePath $cerPath | Out-Null

Write-Host ""
Write-Host "[OK] Self-signed cert erstellt" -ForegroundColor Green
Write-Host "Subject: $($cert.Subject)"
Write-Host "Thumbprint: $($cert.Thumbprint)"
Write-Host "PFX: $pfxPath"
Write-Host "CER: $cerPath"
Write-Host ""
Write-Host "Build mit PFX:" -ForegroundColor Cyan
Write-Host "`$env:CODE_SIGN_PFX=""$((Resolve-Path $pfxPath).Path)"""
Write-Host "`$env:CODE_SIGN_PFX_PASSWORD=""$PfxPassword"""
Write-Host "powershell -ExecutionPolicy Bypass -File .\scripts\build-exe.ps1"
Write-Host ""
Write-Host "Oder mit Thumbprint:" -ForegroundColor Cyan
Write-Host "`$env:CODE_SIGN_CERT_SHA1=""$($cert.Thumbprint)"""
Write-Host "powershell -ExecutionPolicy Bypass -File .\scripts\build-exe.ps1"
