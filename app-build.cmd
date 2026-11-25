@echo off
rem Runs the JVM tests and builds the debug app.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\app-build.ps1" %*
exit /b %ERRORLEVEL%
