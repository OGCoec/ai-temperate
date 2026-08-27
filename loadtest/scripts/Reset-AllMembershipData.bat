@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

where pwsh >nul 2>&1
if errorlevel 1 (
    echo RESET_FAILED: pwsh was not found in PATH.
    pause
    exit /b 1
)

pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%Reset-AllMembershipData.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo Membership full reset failed with exit code %EXIT_CODE%.
) else (
    echo Membership full reset completed successfully.
)

pause
exit /b %EXIT_CODE%
