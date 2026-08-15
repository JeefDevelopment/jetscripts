@echo off
setlocal

rem Usage:
rem   sync.bat init     First run only; previews and initializes two-way state.
rem   sync.bat          Run one synchronization.
rem   sync.bat watch    Keep synchronizing every 15 seconds.

set "MODE=%~1"
if not defined MODE set "MODE=once"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass ^
  -File "%~dp0rclone-sync.ps1" -Mode "%MODE%"

exit /b %ERRORLEVEL%
