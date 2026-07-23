@echo off
setlocal EnableExtensions
chcp 65001 >nul
title ai-temperate local HTTPS dev launcher
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\https\start-local-https-dev.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo Local HTTPS dev launcher failed with exit code: %EXIT_CODE%
  pause
)

exit /b %EXIT_CODE%
