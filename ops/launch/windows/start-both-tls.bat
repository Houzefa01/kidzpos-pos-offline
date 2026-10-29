@echo off
REM Double-clic pour lancer central + store en HTTPS (Caddy, ports 443/4443).
REM ⚠ Premier lancement : Caddy installera sa CA locale (peut demander l'UAC).
powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0launch-both.ps1" -Tls
