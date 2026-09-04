@echo off
rem Launches the MAZEWARD versus-AI bridge (ai/mc_brain.py).
rem
rem ASCII only on purpose: cmd.exe decodes a .bat with the codepage that is
rem active when it reads the line, so non-ASCII text here turns into garbage
rem commands before "chcp 65001" can take effect. Japanese messages live in
rem start-ai.ps1, which PowerShell reads correctly (UTF-8 with BOM).
rem
rem Examples:
rem   start-ai.bat
rem   start-ai.bat --model ppo_gen35.pt
rem   start-ai.bat --port 25578 --greedy

chcp 65001 > nul
setlocal enabledelayedexpansion

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-ai.ps1" %*

pause
