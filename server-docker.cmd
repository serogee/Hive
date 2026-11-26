@echo off
rem Runs the production server with Postgres in Docker. Usage: server-docker [--down | --reset]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\server-docker.ps1" %*
exit /b %ERRORLEVEL%
