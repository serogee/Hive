@echo off
rem Installs and opens the debug app on the connected phone or emulator. Usage: app-dev [server URL]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\app-dev.ps1" %*
exit /b %ERRORLEVEL%
