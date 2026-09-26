@echo off
rem TapTill KDS - double-click to configure the URL/scope/name and build the APK.
rem Runs kds.ps1; any arguments are passed through (e.g. kds.cmd -Show).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0kds.ps1" %*
echo.
pause
