@echo off
rem Builds the server and runs it, with the settings in server\.env.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\server-run.ps1" %*
exit /b %ERRORLEVEL%
