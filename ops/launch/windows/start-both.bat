@echo off
REM Double-clic pour lancer central + store (HTTP, ports 3000/4001).
REM Ouvre une fenêtre PowerShell qui exécute launch-both.ps1.
REM Garde la fenêtre ouverte (-NoExit) pour voir les messages de démarrage.
powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0launch-both.ps1"
