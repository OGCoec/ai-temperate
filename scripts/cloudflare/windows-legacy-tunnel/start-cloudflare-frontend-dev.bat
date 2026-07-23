@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "SCRIPT_PATH=%SCRIPT_DIR%start-cloudflare.ps1"
set "CF_FRONTEND_DEV_TUNNEL_ID=16698f57-7037-4252-adfe-4cc1319bf55c"

if not exist "%POWERSHELL_EXE%" (
  echo ERROR: powershell.exe not found: %POWERSHELL_EXE%
  pause
  exit /b 1
)

if not exist "%SCRIPT_PATH%" (
  echo ERROR: start script not found: %SCRIPT_PATH%
  pause
  exit /b 1
)

"%POWERSHELL_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_PATH%" -Profile frontend-dev %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo ERROR: frontend development Cloudflare tunnel start exited with code %EXIT_CODE%.
  pause
)

exit /b %EXIT_CODE%
