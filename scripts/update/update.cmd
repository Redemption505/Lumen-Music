@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0apply-update.ps1" %*
set "status=%errorlevel%"
echo.
if not "%status%"=="0" echo Update failed. Existing installation was left intact or restored.
pause
exit /b %status%
