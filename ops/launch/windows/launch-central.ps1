# ─────────────────────────────────────────────────────────────────────────────
# launch-central.ps1 — Démarre le nœud CENTRAL sur Windows (100% hors-ligne).
#
#   API :8080 (profile central)
#   UI  :3000 (servie par serve-static.mjs en Node natif)
#   DB  : kidzpos_central (Postgres local)
#
# Variante TLS : passer -Tls pour démarrer Caddy en HTTPS (:443 par défaut).
#
# Usage :
#   .\ops\launch\windows\launch-central.ps1
#   .\ops\launch\windows\launch-central.ps1 -Tls
#   .\ops\launch\windows\launch-central.ps1 -Tls -HttpsPort 8443
# ─────────────────────────────────────────────────────────────────────────────
[CmdletBinding()]
param(
    [switch]$Tls,
    [int]$HttpsPort = 443,
    [int]$Port = 8080,
    [int]$FrontendPort = 3000
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

Require-Java; Require-Node
if ($Tls) { Require-Caddy }
$jar = Resolve-Jar
$dist = if ($env:DIST_CENTRAL) { $env:DIST_CENTRAL } else { Join-Path $Root 'dist-central' }
Require-Dist $dist

# ── Identité / profil ────────────────────────────────────────────────────────
$env:SPRING_PROFILES_ACTIVE = 'central'
$env:SERVER_PORT = "$Port"
if ($Tls) { $env:SERVER_ADDRESS = '127.0.0.1' }  # backend loopback derrière Caddy
$env:KIDZPOS_NODE_ID = if ($env:KIDZPOS_NODE_ID) { $env:KIDZPOS_NODE_ID } else { 'central' }
$env:KIDZPOS_NODE_STORE_ID = ''   # central = agrège tous les stores

# ── Secrets ──────────────────────────────────────────────────────────────────
if (-not $env:JWT_SECRET) {
    $env:JWT_SECRET = 'central-jwt-secret-must-be-at-least-32-bytes-long-xyz'
}
if (-not $env:KIDZPOS_SYNC_INBOUND_API_KEY) {
    $env:KIDZPOS_SYNC_INBOUND_API_KEY = 'test-sync-key-shared-123456789'
}
if (-not $env:KIDZPOS_ADMIN_PASSWORD) {
    $env:KIDZPOS_ADMIN_PASSWORD = 'admin123'
}

# ── DB ───────────────────────────────────────────────────────────────────────
if (-not $env:DB_HOST)     { $env:DB_HOST     = 'localhost' }
if (-not $env:DB_PORT)     { $env:DB_PORT     = '5432' }
if (-not $env:DB_NAME)     { $env:DB_NAME     = 'kidzpos_central' }
if (-not $env:DB_USER)     { $env:DB_USER     = 'kidzpos' }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'kidzpos' }

# ── Inbox processor ──────────────────────────────────────────────────────────
$env:KIDZPOS_SYNC_INBOX_ENABLED = 'true'
if (-not $env:KIDZPOS_SYNC_INBOX_INTERVAL_MS)     { $env:KIDZPOS_SYNC_INBOX_INTERVAL_MS     = '5000' }
if (-not $env:KIDZPOS_SYNC_INBOX_INITIAL_DELAY_MS){ $env:KIDZPOS_SYNC_INBOX_INITIAL_DELAY_MS = '2000' }

# ── Cookies ──────────────────────────────────────────────────────────────────
if (-not $env:KIDZPOS_AUTH_COOKIE_NAME)      { $env:KIDZPOS_AUTH_COOKIE_NAME      = 'kidzpos_rt_central' }
if (-not $env:KIDZPOS_AUTH_COOKIE_SAME_SITE) { $env:KIDZPOS_AUTH_COOKIE_SAME_SITE = 'Lax' }
if (-not $env:KIDZPOS_AUTH_COOKIE_SECURE) {
    $env:KIDZPOS_AUTH_COOKIE_SECURE = if ($Tls) { 'true' } else { 'false' }
}
if (-not $env:KIDZPOS_DEBUG_ENABLED) { $env:KIDZPOS_DEBUG_ENABLED = 'true' }

# ── CORS LAN ─────────────────────────────────────────────────────────────────
$lan = Get-LanIp
if (-not $env:CORS_ALLOWED_ORIGINS) {
    if ($Tls) {
        $env:CORS_ALLOWED_ORIGINS = "https://localhost:$HttpsPort,https://127.0.0.1:$HttpsPort,https://${lan}:$HttpsPort"
    } else {
        $env:CORS_ALLOWED_ORIGINS = "http://localhost:$FrontendPort,http://127.0.0.1:$FrontendPort,http://${lan}:$FrontendPort,http://${lan}:$Port,http://192.168.*.*:$FrontendPort,http://10.*.*.*:$FrontendPort,http://172.*.*.*:$FrontendPort"
    }
}

# ── Conflits de ports ────────────────────────────────────────────────────────
if (Test-PortBusy $Port)         { Die "port backend $Port déjà occupé (central déjà lancé ? .\ops\launch\windows\stop.ps1)" }
if ($Tls -and (Test-PortBusy $HttpsPort)) { Die "port HTTPS $HttpsPort déjà occupé" }
if (-not $Tls -and (Test-PortBusy $FrontendPort)) { Die "port frontend $FrontendPort déjà occupé" }

# ── Démarrage ────────────────────────────────────────────────────────────────
if ($Tls) {
    Write-Host "▶ CENTRAL TLS  HTTPS=:$HttpsPort (Caddy)  backend=loopback:$Port  DB=$($env:DB_NAME)  LAN=$lan" -ForegroundColor Cyan
    Start-Backend 'central' $jar
    Wait-Port $Port 'central backend' | Out-Null
    Start-Caddy 'central' $HttpsPort $Port $dist
    Wait-Port $HttpsPort 'central HTTPS (Caddy)' | Out-Null
    Write-Host "  → local : https://localhost:$HttpsPort"
    Write-Host "  → LAN   : https://${lan}:$HttpsPort  (depuis les autres machines)"
} else {
    Write-Host "▶ CENTRAL  profile=central  API=:$Port  UI=:$FrontendPort  DB=$($env:DB_NAME)  LAN=$lan" -ForegroundColor Cyan
    Start-Backend 'central' $jar
    Start-Frontend 'central' $dist $FrontendPort
    Wait-Port $Port 'central API' | Out-Null
    Write-Host "  → local : http://localhost:$FrontendPort"
    Write-Host "  → LAN   : http://${lan}:$FrontendPort  (depuis les autres machines)   API: http://${lan}:$Port"
}
Write-Host "  → admin@kidzpos.com / `$env:KIDZPOS_ADMIN_PASSWORD   ·   stop: $PSScriptRoot\stop.ps1"
