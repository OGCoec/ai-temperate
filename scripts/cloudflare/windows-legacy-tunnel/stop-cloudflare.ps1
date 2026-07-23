param(
  [ValidateSet("frontend", "frontend-dev", "admin", "api")]
  [string]$Profile
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$tunnelProfiles = @{
  frontend = @{
    DisplayName = "NicoSite frontend (legacy root-domain rollback)"
    PidFileName = "cloudflared-frontend.pid.json"
    StopRequestFileName = "frontend.stop.request"
  }
  "frontend-dev" = @{
    DisplayName = "NicoSite frontend development"
    PidFileName = "cloudflared-frontend-dev.pid.json"
    StopRequestFileName = "frontend-dev.stop.request"
  }
  admin = @{
    DisplayName = "NicoSite admin"
    PidFileName = "cloudflared-admin.pid.json"
    StopRequestFileName = "admin.stop.request"
  }
  api = @{
    DisplayName = "NicoSite API"
    PidFileName = "cloudflared-api.pid.json"
    StopRequestFileName = "api.stop.request"
  }
}

function Read-TunnelProfile {
  while ($true) {
    $value = (Read-Host "Tunnel profile to stop (frontend/frontend-dev/admin/api)").Trim().ToLowerInvariant()
    if ($value -in @("frontend", "frontend-dev", "admin", "api")) {
      return $value
    }
    Write-Host "Invalid profile. Please enter frontend, frontend-dev, admin, or api." -ForegroundColor Yellow
  }
}

if ([string]::IsNullOrWhiteSpace($Profile)) {
  $Profile = Read-TunnelProfile
}
$Profile = $Profile.ToLowerInvariant()
$profileSpec = $tunnelProfiles[$Profile]

$runtimeDirectory = Join-Path $env:LOCALAPPDATA "ai-temperate\cloudflare"
$pidFilePath = Join-Path $runtimeDirectory $profileSpec.PidFileName
$stopRequestPath = Join-Path $runtimeDirectory $profileSpec.StopRequestFileName

if (-not (Test-Path -LiteralPath $pidFilePath)) {
  Write-Host "No running $($profileSpec.DisplayName) Cloudflare tunnel PID file was found."
  exit 0
}

try {
  $state = Get-Content -Raw -Encoding utf8 -LiteralPath $pidFilePath | ConvertFrom-Json
  $processId = [int]$state.processId
  if ($processId -le 0) {
    throw "The PID state file contains an invalid process id."
  }

  $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
  if ($null -eq $process) {
    Remove-Item -LiteralPath $pidFilePath -Force
    if (Test-Path -LiteralPath $stopRequestPath) {
      Remove-Item -LiteralPath $stopRequestPath -Force
    }
    Write-Host "The $($profileSpec.DisplayName) tunnel process has already exited. Stale PID state was cleaned."
    exit 0
  }

  if ($process.ProcessName -ne "cloudflared") {
    throw "PID=$processId is not cloudflared. Refusing to stop that process."
  }

  $expectedPath = [string]$state.executablePath
  if (-not [string]::IsNullOrWhiteSpace($expectedPath) -and -not [string]::IsNullOrWhiteSpace($process.Path)) {
    $actualPath = (Resolve-Path -LiteralPath $process.Path).Path
    $resolvedExpectedPath = (Resolve-Path -LiteralPath $expectedPath).Path
    if (-not $actualPath.Equals($resolvedExpectedPath, [StringComparison]::OrdinalIgnoreCase)) {
      throw "PID=$processId executable path does not match the recorded cloudflared path."
    }
  }

  $expectedStartTime = [DateTime]::Parse([string]$state.processStartTimeUtc).ToUniversalTime()
  $actualStartTime = $process.StartTime.ToUniversalTime()
  if ([Math]::Abs(($actualStartTime - $expectedStartTime).TotalSeconds) -gt 2) {
    throw "PID=$processId appears to have been reused by another process."
  }

  New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
  [System.IO.File]::WriteAllText($stopRequestPath, [DateTime]::UtcNow.ToString('O'), [System.Text.UTF8Encoding]::new($false))
  Stop-Process -Id $processId -Force
  Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $pidFilePath) {
    Remove-Item -LiteralPath $pidFilePath -Force
  }
  Write-Host "Stopped $($profileSpec.DisplayName) Cloudflare tunnel. PID=$processId." -ForegroundColor Green
  exit 0
} catch {
  Write-Host "Failed to stop $($profileSpec.DisplayName) Cloudflare tunnel: $($_.Exception.Message)" -ForegroundColor Red
  exit 1
}
