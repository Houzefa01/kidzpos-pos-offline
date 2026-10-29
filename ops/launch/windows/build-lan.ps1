# ─────────────────────────────────────────────────────────────────────────────
# build-lan.ps1 — (Re)build des frontends câblés sur l'IP LAN du serveur,
# pour que les autres machines du LAN puissent accéder à l'UI.
#
# `VITE_API_URL` est figé au build et prioritaire dans le code. S'il vaut
# `http://localhost:8080`, un navigateur distant appelle SON propre localhost
# → échec. On le re-pose sur `http://<IP-LAN>:<port>` (ou https si -Https).
#
# Usage :
#   .\ops\launch\windows\build-lan.ps1                   # both, HTTP, IP auto
#   .\ops\launch\windows\build-lan.ps1 -Target central
#   .\ops\launch\windows\build-lan.ps1 -Ip 192.168.1.10 -Https
# ─────────────────────────────────────────────────────────────────────────────
[CmdletBinding()]
param(
    [ValidateSet('central','store','both')] [string]$Target = 'both',
    [string]$Ip = '',
    [switch]$Https
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

if (-not $Ip) { $Ip = Get-LanIp }
if ($Https) {
    $centralUrl = "https://$Ip"
    $storeUrl   = "https://${Ip}:4443"
    $mode = 'HTTPS (Caddy)'
} else {
    $centralUrl = "http://${Ip}:8080"
    $storeUrl   = "http://${Ip}:8081"
    $mode = 'HTTP'
}
if ($env:CENTRAL_API_URL) { $centralUrl = $env:CENTRAL_API_URL }
if ($env:STORE_API_URL)   { $storeUrl   = $env:STORE_API_URL }

Require-Cmd npm "Installer Node.js (voir install.ps1)"
if (-not (Test-Path (Join-Path $Root 'node_modules'))) {
    Die "node_modules absent → 'npm ci' une fois (avec internet)"
}

function Build-One([string]$distName, [string]$apiUrl) {
    Write-Host "  → build $distName câblé sur $apiUrl"
    Push-Location $Root
    try {
        $env:VITE_API_URL = $apiUrl
        & npm run build | Out-Null
    } finally { Pop-Location }
    $dest = Join-Path $Root $distName
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Copy-Item (Join-Path $Root 'dist') $dest -Recurse
}

Write-Host "▶ build-lan [$mode] : IP=$Ip  central=$centralUrl  store=$storeUrl" -ForegroundColor Cyan
switch ($Target) {
    'central' { Build-One 'dist-central' $centralUrl }
    'store'   { Build-One 'dist-store'   $storeUrl }
    'both'    {
        Build-One 'dist-central' $centralUrl
        Build-One 'dist-store'   $storeUrl
    }
}
$launchScript = if ($Https) { 'launch-both.ps1 -Tls' } else { 'launch-both.ps1' }
Write-Host "✓ build terminé. Lancer : .\ops\launch\windows\$launchScript" -ForegroundColor Green
