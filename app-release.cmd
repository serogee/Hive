@echo off
rem Makes a GitHub release from main. Usage: app-release 0.1.0, or app-release --setup-key one time first.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\app-release.ps1" %*
exit /b %ERRORLEVEL%
