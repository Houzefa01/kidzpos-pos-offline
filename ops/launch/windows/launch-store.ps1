# ─────────────────────────────────────────────────────────────────────────────
# launch-store.ps1 — Démarre un nœud STORE (s1) sur Windows.
#
#   API :8081 (profile local, storeId=s1)
#   UI  :4001 (servie par serve-static.mjs en Node natif)
#   DB  : kidzpos_store_s1 (Postgres local)
#   Sync push/pull → KIDZPOS_SYNC_CENTRAL_URL (défaut http://localhost:8080)
#
# Variante TLS : passer -Tls pour démarrer Caddy en HTTPS (:4443 par défaut).
#
# Usage :
#   .\ops\launch\windows\launch-store.ps1
#   .\ops\launch\windows\launch-store.ps1 -Tls
# ─────────────────────────────────────────────────────────────────────────────
[CmdletBinding()]
param(
    [switch]$Tls,
    [int]$HttpsPort = 4443,
    [int]$Port = 8081,
    [int]$FrontendPort = 4001
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

Require-Java; Require-Node
if ($Tls) { Require-Caddy }
$jar = Resolve-Jar
$dist = if ($env:DIST_STORE) { $env:DIST_STORE } else { Join-Path $Root 'dist-store' }
Require-Dist $dist

# ── Identité / profil ────────────────────────────────────────────────────────
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_PORT = "$Port"
if ($Tls) { $env:SERVER_ADDRESS = '127.0.0.1' }
$env:KIDZPOS_NODE_ID = if ($env:KIDZPOS_NODE_ID) { $env:KIDZPOS_NODE_ID } else { 'store-s1' }
$env:KIDZPOS_NODE_STORE_ID = if ($env:KIDZPOS_NODE_STORE_ID) { $env:KIDZPOS_NODE_STORE_ID } else { 's1' }

# ── Secrets ──────────────────────────────────────────────────────────────────
if (-not $env:JWT_SECRET) {
    $env:JWT_SECRET = 'store-s1-jwt-secret-must-be-at-least-32-bytes-long-abc'
}
if (-not $env:KIDZPOS_SYNC_API_KEY) {
    $env:KIDZPOS_SYNC_API_KEY = 'test-sync-key-shared-123456789'
}
if (-not $env:KIDZPOS_ADMIN_PASSWORD) {
    $env:KIDZPOS_ADMIN_PASSWORD = 'admin123'
}

# ── DB ───────────────────────────────────────────────────────────────────────
if (-not $env:DB_HOST)     { $env:DB_HOST     = 'localhost' }
if (-not $env:DB_PORT)     { $env:DB_PORT     = '5432' }
if (-not $env:DB_NAME)     { $env:DB_NAME     = 'kidzpos_store_s1' }
if (-not $env:DB_USER)     { $env:DB_USER     = 'kidzpos' }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'kidzpos' }

# ── Sync vers le central ─────────────────────────────────────────────────────
if (-not $env:KIDZPOS_SYNC_CENTRAL_URL) { $env:KIDZPOS_SYNC_CENTRAL_URL = 'http://localhost:8080' }
$env:KIDZPOS_SYNC_PUSH_ENABLED = 'true'
$env:KIDZPOS_SYNC_PULL_ENABLED = 'true'
$env:KIDZPOS_SYNC_INBOX_ENABLED = 'true'
if (-not $env:KIDZPOS_SYNC_INTERVAL_MS)        { $env:KIDZPOS_SYNC_INTERVAL_MS        = '5000' }
if (-not $env:KIDZPOS_SYNC_PULL_INTERVAL_MS)   { $env:KIDZPOS_SYNC_PULL_INTERVAL_MS   = '10000' }
if (-not $env:KIDZPOS_SYNC_INBOX_INTERVAL_MS)  { $env:KIDZPOS_SYNC_INBOX_INTERVAL_MS  = '10000' }

# ── Cookies ──────────────────────────────────────────────────────────────────
if (-not $env:KIDZPOS_AUTH_COOKIE_NAME)      { $env:KIDZPOS_AUTH_COOKIE_NAME      = 'kidzpos_rt_s1' }
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
if (Test-PortBusy $Port) { Die "port backend $Port déjà occupé (store déjà lancé ? stop.ps1)" }
if ($Tls -and (Test-PortBusy $HttpsPort)) { Die "port HTTPS $HttpsPort déjà occupé" }
if (-not $Tls -and (Test-PortBusy $FrontendPort)) { Die "port frontend $FrontendPort déjà occupé" }

# ── Démarrage ────────────────────────────────────────────────────────────────
if ($Tls) {
    Write-Host "▶ STORE s1 TLS  HTTPS=:$HttpsPort (Caddy)  backend=loopback:$Port  DB=$($env:DB_NAME)  central=$($env:KIDZPOS_SYNC_CENTRAL_URL)  LAN=$lan" -ForegroundColor Cyan
    Start-Backend 'store-s1' $jar
    Wait-Port $Port 'store backend' | Out-Null
    Start-Caddy 'store-s1' $HttpsPort $Port $dist
    Wait-Port $HttpsPort 'store HTTPS (Caddy)' | Out-Null
    Write-Host "  → local : https://localhost:$HttpsPort"
    Write-Host "  → LAN   : https://${lan}:$HttpsPort"
} else {
    Write-Host "▶ STORE s1  profile=local  API=:$Port  UI=:$FrontendPort  DB=$($env:DB_NAME)  central=$($env:KIDZPOS_SYNC_CENTRAL_URL)  LAN=$lan" -ForegroundColor Cyan
    Start-Backend 'store-s1' $jar
    Start-Frontend 'store-s1' $dist $FrontendPort
    Wait-Port $Port 'store API' | Out-Null
    Write-Host "  → local : http://localhost:$FrontendPort"
    Write-Host "  → LAN   : http://${lan}:$FrontendPort  (depuis les autres machines)   API: http://${lan}:$Port"
}
Write-Host "  → admin@kidzpos.com / `$env:KIDZPOS_ADMIN_PASSWORD   ·   stop: $PSScriptRoot\stop.ps1"
