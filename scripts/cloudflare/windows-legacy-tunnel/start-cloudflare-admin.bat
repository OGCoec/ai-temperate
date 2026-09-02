@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "PWSH_EXE=pwsh.exe"
set "SCRIPT_PATH=%SCRIPT_DIR%start-cloudflare.ps1"

where "%PWSH_EXE%" >nul 2>nul
if errorlevel 1 (
  echo ERROR: pwsh.exe not found in PATH. Install PowerShell 7 first.
  pause
  exit /b 1
)

if not exist "%SCRIPT_PATH%" (
  echo ERROR: start script not found: %SCRIPT_PATH%
  pause
  exit /b 1
)

"%PWSH_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_PATH%" -Profile admin %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo ERROR: admin Cloudflare tunnel start exited with code %EXIT_CODE%.
  pause
)

exit /b %EXIT_CODE%
