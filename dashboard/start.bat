@echo off
rem コンソール付きで起動する。起動しないときはここでエラーが読める。
cd /d "%~dp0"
set PYTHONIOENCODING=utf-8
python launcher.py
pause
