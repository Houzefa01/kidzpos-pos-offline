@echo off
REM Double-clic pour lancer uniquement le store s1 (HTTP, port 4001).
powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0launch-store.ps1"
