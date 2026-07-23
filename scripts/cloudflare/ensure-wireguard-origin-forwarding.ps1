[CmdletBinding()]
param(
  [ValidateRange(10, 600)]
  [int]$TimeoutSeconds = 120,
  [ValidateRange(1, 15)]
  [int]$PollSeconds = 2
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$serviceName = 'WireGuardTunnel$ait-origin'
$interfaceAlias = 'ait-origin'
$wireGuardAddress = '10.66.0.2'
$applyScript = Join-Path $PSScriptRoot 'configure-wireguard-origin-forwarding.ps1'
$logDirectory = Join-Path $env:ProgramData 'ai-temperate\wireguard'
$logPath = Join-Path $logDirectory 'origin-forwarding-startup.log'

function Write-StartupLog {
  param([Parameter(Mandatory = $true)][string]$Message)

  $line = "{0:u} {1}" -f [DateTime]::UtcNow, $Message
  try {
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
  } catch {
    # Logging failure must not block the forwarding operation.
  }
}

try {
  if (-not (Test-Path -LiteralPath $applyScript)) {
    throw "Forwarding script not found: $applyScript"
  }

  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    $address = Get-NetIPAddress `
      -InterfaceAlias $interfaceAlias `
      -AddressFamily IPv4 `
      -IPAddress $wireGuardAddress `
      -ErrorAction SilentlyContinue

    if ($null -ne $service -and
        $service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Running -and
        $null -ne $address) {
      Write-StartupLog "WireGuard is ready; applying origin forwarding rules."
      & $applyScript -Action Apply
      Write-StartupLog "Origin forwarding rules applied successfully."
      exit 0
    }

    Start-Sleep -Seconds $PollSeconds
  } while ([DateTime]::UtcNow -lt $deadline)

  throw "WireGuard service or address $wireGuardAddress was not ready within $TimeoutSeconds seconds."
} catch {
  Write-StartupLog "Origin forwarding startup task failed: $($_.Exception.Message)"
  exit 1
}
