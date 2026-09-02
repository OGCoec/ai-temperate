@echo off
setlocal EnableExtensions

if /I "%~1"=="--elevated" goto elevated

pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
  "Start-Process -FilePath '%~f0' -ArgumentList '--elevated' -Verb RunAs"
exit /b %ERRORLEVEL%

:elevated
set "SCRIPT_DIR=%~dp0"
set "WINGET_EXE=%LOCALAPPDATA%\Microsoft\WindowsApps\winget.exe"
set "INSTALL_SCRIPT=%SCRIPT_DIR%scripts\cloudflare\install-wireguard-origin-client.ps1"
set "FORWARD_SCRIPT=%SCRIPT_DIR%scripts\cloudflare\configure-wireguard-origin-forwarding.ps1"
set "REGISTER_SCRIPT=%SCRIPT_DIR%scripts\cloudflare\register-wireguard-origin-startup.ps1"
set "SERVER_PUBLIC_KEY=UOvfkl1e0XFP9NBuDqHWRZ6jsgFQVjKv+et3T2rpr00="

if not exist "%WINGET_EXE%" (
  echo ERROR: winget.exe not found: %WINGET_EXE%
  pause
  exit /b 1
)
if not exist "%INSTALL_SCRIPT%" (
  echo ERROR: WireGuard install script not found: %INSTALL_SCRIPT%
  pause
  exit /b 1
)
if not exist "%FORWARD_SCRIPT%" (
  echo ERROR: WireGuard forwarding script not found: %FORWARD_SCRIPT%
  pause
  exit /b 1
)
if not exist "%REGISTER_SCRIPT%" (
  echo ERROR: WireGuard startup registration script not found: %REGISTER_SCRIPT%
  pause
  exit /b 1
)

echo Installing or confirming official WireGuard...
"%WINGET_EXE%" install --id WireGuard.WireGuard --exact --silent ^
  --accept-package-agreements --accept-source-agreements --disable-interactivity
if errorlevel 1 (
  echo ERROR: WireGuard installation failed.
  pause
  exit /b 1
)

pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%INSTALL_SCRIPT%" ^
  -ServerPublicKey "%SERVER_PUBLIC_KEY%"
if errorlevel 1 (
  echo ERROR: WireGuard tunnel configuration failed.
  pause
  exit /b 1
)

pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%FORWARD_SCRIPT%" ^
  -Action Apply
if errorlevel 1 (
  echo ERROR: Restricted origin forwarding configuration failed.
  pause
  exit /b 1
)

echo Registering WireGuard origin forwarding startup task...
pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%REGISTER_SCRIPT%" ^
  -Action Register
if errorlevel 1 (
  echo ERROR: WireGuard origin startup task registration failed.
  pause
  exit /b 1
)

echo.
echo Azure WireGuard origin setup completed.
echo Keep this window open so the result can be reviewed.
pause
exit /b 0
