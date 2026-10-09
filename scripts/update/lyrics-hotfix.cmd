@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0apply-lyrics-hotfix.ps1" %*
set "status=%errorlevel%"
echo.
if not "%status%"=="0" echo Lyrics hotfix failed. Existing installation was left intact or restored.
pause
exit /b %status%
