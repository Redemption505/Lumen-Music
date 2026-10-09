@echo off
setlocal DisableDelayedExpansion
set "INSTALL=%~1"
if defined INSTALL goto apply
set /p "INSTALL=Install directory (Enter for %LOCALAPPDATA%\LumenMusic, Q to cancel): "
if not defined INSTALL set "INSTALL=%LOCALAPPDATA%\LumenMusic"
:apply
set "INSTALL=%INSTALL:"=%"
if /i "%INSTALL%"=="Q" exit /b 1
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0apply-local-update.ps1" -InstallRoot "%INSTALL%" -PackageRoot "%~dp0." -ResultPath "%~dp0stage\result.xml"
set "RESULT=%ERRORLEVEL%"
echo Result: %~dp0stage\result.xml
pause
exit /b %RESULT%
