# ─────────────────────────────────────────────────────────────────────────────
# common.ps1 — Helpers PowerShell partagés (équivalent du bash common.sh).
#
# Pas d'appel internet sortant : on n'utilise QUE le JAR déjà buildé, Postgres
# local et un serveur statique Node natif. Readiness via TcpClient natif .NET
# (pas de curl, pas de proxy, pas d'internet).
# ─────────────────────────────────────────────────────────────────────────────

$script:Root    = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$script:LogDir  = Join-Path $Root 'logs'
$script:Serve   = Join-Path $PSScriptRoot '..\serve-static.mjs'   # même Node script que Linux
$script:CaddyCfg = Join-Path $PSScriptRoot '..\Caddyfile'

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Die([string]$msg) {
    Write-Host "❌ $msg" -ForegroundColor Red
    exit 1
}

# IP LAN primaire — filtre boucle / VirtualBox / WSL / Hyper-V.
function Get-LanIp {
    if ($env:LAN_IP) { return $env:LAN_IP }
    $candidates = Get-NetIPAddress -AddressFamily IPv4 -PrefixOrigin Manual,Dhcp -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notmatch '^127\.' -and
            $_.IPAddress -notmatch '^169\.254\.' -and
            $_.InterfaceAlias -notmatch '(Loopback|vEthernet|VirtualBox|WSL)'
        } | Sort-Object -Property InterfaceMetric
    if ($candidates) { return $candidates[0].IPAddress }
    return '127.0.0.1'
}

function Require-Cmd([string]$cmd, [string]$hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Die "$cmd introuvable. $hint"
    }
}

function Require-Java { Require-Cmd 'java' 'Installer OpenJDK 17 (voir install.ps1)' }
function Require-Node { Require-Cmd 'node' 'Installer Node.js LTS (voir install.ps1)' }
function Require-Caddy { Require-Cmd 'caddy' 'Installer Caddy (voir install.ps1)' }

# Localise le JAR backend le plus récent (hors -original).
function Resolve-Jar {
    $pattern = Join-Path $Root 'backend\target\kidzpos-backend-*.jar'
    $jars = Get-ChildItem $pattern -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*.original.jar' } |
            Sort-Object LastWriteTime -Descending
    if (-not $jars) {
        Die "JAR backend absent → build une fois (avec internet) : cd backend ; mvn -DskipTests package"
    }
    return $jars[0].FullName
}

function Require-Dist([string]$dist) {
    if (-not (Test-Path (Join-Path $dist 'index.html'))) {
        Die "frontend '$dist' absent → build une fois : npm ci puis npm run build (ou build-lan.ps1)"
    }
}

# Test rapide « port LOCAL accepte des connexions » (équivalent bash /dev/tcp).
function Test-PortBusy([int]$port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect('127.0.0.1', $port, $null, $null)
        $success = $iar.AsyncWaitHandle.WaitOne(200, $false)
        if ($success) { $client.EndConnect($iar) | Out-Null }
        $busy = $client.Connected
        $client.Close()
        return $busy
    } catch { return $false }
}

# Bloque jusqu'à ~120s qu'un port local soit prêt.
function Wait-Port([int]$port, [string]$label) {
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-PortBusy $port) {
            Write-Host "  ✓ $label prêt (port $port)" -ForegroundColor Green
            return $true
        }
        Start-Sleep -Seconds 2
    }
    Write-Host "  ⚠ $label toujours pas prêt après 120s — voir $LogDir" -ForegroundColor Yellow
    return $false
}

# Démarre un backend Java en arrière-plan, PID dans logs/<name>.pid.
function Start-Backend([string]$name, [string]$jar) {
    $logFile = Join-Path $LogDir "$name.log"
    $proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar) `
                          -WorkingDirectory $Root `
                          -RedirectStandardOutput $logFile `
                          -RedirectStandardError  (Join-Path $LogDir "$name.err.log") `
                          -WindowStyle Hidden -PassThru
    $proc.Id | Out-File -FilePath (Join-Path $LogDir "$name.pid") -Encoding ascii -NoNewline
    Write-Host "  backend PID $($proc.Id)  → log: $logFile"
}

# Démarre serve-static.mjs en arrière-plan.
function Start-Frontend([string]$name, [string]$dist, [int]$port) {
    $logFile = Join-Path $LogDir "$name-frontend.log"
    $proc = Start-Process -FilePath 'node' -ArgumentList @($Serve, $dist, $port) `
                          -WorkingDirectory $Root `
                          -RedirectStandardOutput $logFile `
                          -RedirectStandardError  (Join-Path $LogDir "$name-frontend.err.log") `
                          -WindowStyle Hidden -PassThru
    $proc.Id | Out-File -FilePath (Join-Path $LogDir "$name-frontend.pid") -Encoding ascii -NoNewline
    Write-Host "  frontend PID $($proc.Id)  → http://localhost:$port"
}

# Démarre Caddy en mode TLS (cf Caddyfile partagé).
function Start-Caddy([string]$name, [int]$httpsPort, [int]$backendPort, [string]$dist) {
    $logFile = Join-Path $LogDir "$name-caddy.log"
    $ip = Get-LanIp
    $env:KIDZPOS_HTTPS_ADDRS = "https://localhost:$httpsPort, https://127.0.0.1:$httpsPort, https://${ip}:$httpsPort"
    $env:KIDZPOS_BACKEND_PORT = "$backendPort"
    $env:KIDZPOS_DIST_DIR = $dist
    $env:KIDZPOS_CADDY_LOG = (Join-Path $LogDir "$name-caddy-access.log")
    $proc = Start-Process -FilePath 'caddy' `
                          -ArgumentList @('run','--config',$CaddyCfg,'--adapter','caddyfile') `
                          -WorkingDirectory $Root `
                          -RedirectStandardOutput $logFile `
                          -RedirectStandardError  (Join-Path $LogDir "$name-caddy.err.log") `
                          -WindowStyle Hidden -PassThru
    $proc.Id | Out-File -FilePath (Join-Path $LogDir "$name-caddy.pid") -Encoding ascii -NoNewline
    Write-Host "  caddy PID $($proc.Id)  → https://localhost:$httpsPort"
}
