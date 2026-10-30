@echo off
rem Starts all dev servers (facade 8080, visitor 8081, stocks 8082, Vite 3000),
rem each in its own window, and stops them all when this window is closed or
rem Ctrl+C is pressed here. See dev.ps1 for details; arguments are passed through
rem (e.g. dev.cmd -NoFrontend).
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev.ps1" %*
