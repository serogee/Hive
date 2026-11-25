@echo off
rem Starts the demo server on port 8080. Usage: server-dev [--reset]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\server-dev.ps1" %*
exit /b %ERRORLEVEL%
