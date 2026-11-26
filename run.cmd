@echo off
rem Runs Hive's commands, like npm run. Type .\run for the list, or double-click for a menu.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run.ps1" %*
exit /b %ERRORLEVEL%
