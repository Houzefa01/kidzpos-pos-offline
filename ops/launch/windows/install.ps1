# ─────────────────────────────────────────────────────────────────────────────
# install.ps1 — Installation des prérequis KidzPOS sur Windows 10/11.
#
# Utilise winget (livré avec Windows 10 21H1+ / Windows 11). Idempotent : tu
# peux relancer sans risque.
#
# Usage (PowerShell en administrateur) :
#   Set-ExecutionPolicy -Scope CurrentUser RemoteSigned -Force
#   .\ops\launch\windows\install.ps1
# ─────────────────────────────────────────────────────────────────────────────
[CmdletBinding()]
param(
    [switch]$SkipPostgres,  # passer si Postgres déjà installé manuellement
    [switch]$SkipBuild      # ne pas faire npm ci + mvn package à la fin
)

$ErrorActionPreference = 'Stop'

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Admin)) {
    Write-Host "Relance PowerShell EN ADMINISTRATEUR puis ré-exécute ce script." -ForegroundColor Red
    exit 1
}

function Has-Winget { (Get-Command winget -ErrorAction SilentlyContinue) -ne $null }
if (-not (Has-Winget)) {
    Write-Host "winget introuvable. Installer 'App Installer' depuis le Microsoft Store, puis relancer." -ForegroundColor Red
    Start-Process "ms-windows-store://pdp/?ProductId=9NBLGGH4NNS1"
    exit 1
}

function Install-Pkg {
    param([string]$Id, [string]$Display)
    Write-Host "▶ $Display" -ForegroundColor Cyan
    winget install --id $Id --silent --accept-source-agreements --accept-package-agreements `
        --disable-interactivity 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) {
        # -1978335189 = APPINSTALLER_CLI_ERROR_PACKAGE_ALREADY_INSTALLED
        Write-Host "   (déjà installé ou code $LASTEXITCODE)" -ForegroundColor DarkGray
    }
}

Write-Host "=== 1/5 OpenJDK 17 ===" -ForegroundColor Yellow
Install-Pkg "Microsoft.OpenJDK.17" "OpenJDK 17 (Microsoft Build)"

Write-Host "=== 2/5 Node.js LTS ===" -ForegroundColor Yellow
Install-Pkg "OpenJS.NodeJS.LTS" "Node.js LTS"

Write-Host "=== 3/5 Apache Maven ===" -ForegroundColor Yellow
Install-Pkg "Apache.Maven" "Apache Maven"

Write-Host "=== 4/5 Caddy ===" -ForegroundColor Yellow
Install-Pkg "CaddyServer.Caddy" "Caddy"

if (-not $SkipPostgres) {
    Write-Host "=== 5/5 PostgreSQL ===" -ForegroundColor Yellow
    Write-Host "  ⚠ L'installateur PostgreSQL est interactif. Note bien :" -ForegroundColor Yellow
    Write-Host "    - Mot de passe choisi pour l'utilisateur 'postgres'" -ForegroundColor Yellow
    Write-Host "    - Port (laisser 5432)" -ForegroundColor Yellow
    Write-Host "    - Locale (Default)" -ForegroundColor Yellow
    Read-Host "  Appuie sur ENTRÉE pour lancer l'installation (ou Ctrl+C pour passer)"
    winget install --id PostgreSQL.PostgreSQL.16 --accept-source-agreements --accept-package-agreements
} else {
    Write-Host "=== 5/5 PostgreSQL : SkipPostgres → ignoré ===" -ForegroundColor DarkGray
}

# Refresh PATH dans la session courante (les nouveaux PATH ne sont pas pris automatiquement)
$env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User")

Write-Host ""
Write-Host "=== Vérification ===" -ForegroundColor Yellow
foreach ($cmd in 'java','node','npm','mvn','caddy','psql') {
    $found = Get-Command $cmd -ErrorAction SilentlyContinue
    if ($found) {
        Write-Host ("  ✓ {0,-6} → {1}" -f $cmd, $found.Source) -ForegroundColor Green
    } else {
        Write-Host ("  ✗ {0} introuvable dans le PATH" -f $cmd) -ForegroundColor Red
    }
}

# ── Création des bases (si Postgres installé et PSQL_PASSWORD fourni) ────────
if (-not $SkipPostgres) {
    Write-Host ""
    Write-Host "=== Bases de données ===" -ForegroundColor Yellow
    $pgpass = Read-Host "  Mot de passe de l'utilisateur 'postgres' (le même qu'à l'install)"
    $env:PGPASSWORD = $pgpass
    foreach ($db in 'kidzpos','kidzpos_central','kidzpos_store_s1') {
        $exists = & psql -U postgres -h localhost -tAc "SELECT 1 FROM pg_database WHERE datname='$db'" 2>$null
        if ($exists -ne '1') {
            Write-Host "  → création DB $db"
            & psql -U postgres -h localhost -c "CREATE DATABASE $db;" | Out-Null
        } else {
            Write-Host "  · DB $db existe déjà"
        }
    }
    $userExists = & psql -U postgres -h localhost -tAc "SELECT 1 FROM pg_roles WHERE rolname='kidzpos'" 2>$null
    if ($userExists -ne '1') {
        Write-Host "  → création user kidzpos"
        & psql -U postgres -h localhost -c "CREATE ROLE kidzpos WITH LOGIN PASSWORD 'kidzpos';" | Out-Null
        foreach ($db in 'kidzpos','kidzpos_central','kidzpos_store_s1') {
            & psql -U postgres -h localhost -c "GRANT ALL PRIVILEGES ON DATABASE $db TO kidzpos;" | Out-Null
            & psql -U postgres -h localhost -d $db -c "GRANT ALL ON SCHEMA public TO kidzpos;" | Out-Null
        }
    }
    Remove-Item env:PGPASSWORD
}

# ── Build (optionnel) ────────────────────────────────────────────────────────
if (-not $SkipBuild) {
    $root = (Resolve-Path "$PSScriptRoot\..\..\..").Path
    Write-Host ""
    Write-Host "=== Build initial (depuis $root) ===" -ForegroundColor Yellow
    Push-Location $root
    try {
        if (Test-Path package.json) {
            Write-Host "  → npm ci"
            & npm ci
        }
        if (Test-Path backend\pom.xml) {
            Write-Host "  → mvn package (skip tests)"
            Push-Location backend
            & mvn -DskipTests package
            Pop-Location
        }
    } finally { Pop-Location }
}

Write-Host ""
Write-Host "✅ Installation Windows terminée." -ForegroundColor Green
Write-Host ""
Write-Host "Lancer KidzPOS :" -ForegroundColor Cyan
Write-Host "  .\ops\launch\windows\launch-both.ps1"
Write-Host "  → ou double-clic sur ops\launch\windows\start-both.bat"
Write-Host ""
Write-Host "Arrêter :"
Write-Host "  .\ops\launch\windows\stop.ps1"
