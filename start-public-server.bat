@echo off
chcp 65001 > nul
setlocal enabledelayedexpansion

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-public-server.ps1"

pause
