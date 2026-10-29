# ─────────────────────────────────────────────────────────────────────────────
# launch-both.ps1 — Démarre central + store (s1) sur Windows.
#
# Usage :
#   .\ops\launch\windows\launch-both.ps1            # HTTP (3000 / 4001)
#   .\ops\launch\windows\launch-both.ps1 -Tls       # HTTPS Caddy (443 / 4443)
# ─────────────────────────────────────────────────────────────────────────────
[CmdletBinding()]
param([switch]$Tls)
$ErrorActionPreference = 'Stop'

& "$PSScriptRoot\launch-central.ps1" -Tls:$Tls
Write-Host ""
& "$PSScriptRoot\launch-store.ps1" -Tls:$Tls
Write-Host ""
if ($Tls) {
    Write-Host "✓ Central + Store lancés en HTTPS." -ForegroundColor Green
    Write-Host "    Central UI → https://localhost"
    Write-Host "    Store   UI → https://localhost:4443"
} else {
    Write-Host "✓ Central + Store lancés." -ForegroundColor Green
    Write-Host "    Central UI → http://localhost:3000"
    Write-Host "    Store   UI → http://localhost:4001"
}
Write-Host "    Stop       → $PSScriptRoot\stop.ps1"
