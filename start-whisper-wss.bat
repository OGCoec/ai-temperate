@echo off
setlocal

title Whisper Medium WSS - Port 7896

pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\whisper-wss-server\start-whisper-wss.ps1" %*
set "WHISPER_EXIT_CODE=%ERRORLEVEL%"

if not "%WHISPER_EXIT_CODE%"=="0" (
  echo.
  echo Whisper WSS failed to start or exited with code %WHISPER_EXIT_CODE%.
  pause
)

exit /b %WHISPER_EXIT_CODE%
