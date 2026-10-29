@echo off
REM Double-clic pour lancer uniquement le central (HTTP, port 3000).
powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0launch-central.ps1"
