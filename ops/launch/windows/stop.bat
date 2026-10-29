@echo off
REM Double-clic pour arrêter tout (backend + frontend + Caddy).
powershell -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
pause
