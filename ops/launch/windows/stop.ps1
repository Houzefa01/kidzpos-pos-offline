# ─────────────────────────────────────────────────────────────────────────────
# stop.ps1 — Arrête central + store (backends + frontends + Caddy) sur Windows.
# ─────────────────────────────────────────────────────────────────────────────
[CmdletBinding()] param()
$ErrorActionPreference = 'SilentlyContinue'

$Root   = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$LogDir = Join-Path $Root 'logs'

$names = @(
    'central','central-frontend','central-caddy',
    'store-s1','store-s1-frontend','store-s1-caddy'
)

foreach ($name in $names) {
    $pidFile = Join-Path $LogDir "$name.pid"
    if (Test-Path $pidFile) {
        $pidVal = Get-Content $pidFile -Raw
        if ($pidVal -match '^\d+$') {
            $proc = Get-Process -Id ([int]$pidVal) -ErrorAction SilentlyContinue
            if ($proc) {
                Stop-Process -Id ([int]$pidVal) -Force -ErrorAction SilentlyContinue
                Write-Host "  ✓ $name (PID $pidVal) arrêté"
            }
        }
        Remove-Item $pidFile -Force
    }
}

# Filet de sécurité : tuer les résidus par leur ligne de commande.
$residual = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.CommandLine -match 'kidzpos-backend-.*\.jar' -or
        $_.CommandLine -match 'serve-static\.mjs' -or
        ($_.CommandLine -match 'caddy.*run.*--config' -and $_.CommandLine -match 'ops\\launch\\Caddyfile')
    }
foreach ($p in $residual) {
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "  ✓ résiduel (PID $($p.ProcessId)) tué"
}

Write-Host "✓ Arrêt terminé." -ForegroundColor Green
