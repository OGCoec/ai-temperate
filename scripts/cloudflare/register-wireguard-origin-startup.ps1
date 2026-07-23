[CmdletBinding()]
param(
  [ValidateSet('Register', 'Unregister')]
  [string]$Action = 'Register'
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$taskName = 'ai-temperate WireGuard origin forwarding'
$ensureScript = Join-Path $PSScriptRoot 'ensure-wireguard-origin-forwarding.ps1'
$powershellPath = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'

function Assert-Administrator {
  $principal = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent())
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Register and Unregister must run from an elevated PowerShell session."
  }
}

Assert-Administrator

if ($Action -eq 'Unregister') {
  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
  Write-Host "Removed scheduled task: $taskName"
  exit 0
}

if (-not (Test-Path -LiteralPath $ensureScript)) {
  throw "Startup helper script not found: $ensureScript"
}

$taskAction = New-ScheduledTaskAction `
  -Execute $powershellPath `
  -Argument ('-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}"' -f $ensureScript)
$taskTrigger = New-ScheduledTaskTrigger -AtStartup
$taskPrincipal = New-ScheduledTaskPrincipal `
  -UserId 'SYSTEM' `
  -LogonType ServiceAccount `
  -RunLevel Highest

try {
  $taskSettings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 3) `
    -MultipleInstances IgnoreNew `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1)
} catch {
  $taskSettings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 3)
}

Register-ScheduledTask `
  -TaskName $taskName `
  -Action $taskAction `
  -Trigger $taskTrigger `
  -Principal $taskPrincipal `
  -Settings $taskSettings `
  -Description 'Wait for the ai-temperate WireGuard interface, then apply restricted origin forwarding.' `
  -Force `
  -ErrorAction Stop | Out-Null

Write-Host "Registered scheduled task: $taskName"
