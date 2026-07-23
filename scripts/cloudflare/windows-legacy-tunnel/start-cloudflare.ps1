param(
  [ValidateSet("frontend", "frontend-dev", "admin", "api")]
  [string]$Profile,
  [ValidateSet("http", "https")]
  [string]$Protocol,
  [ValidateRange(0, 65535)]
  [int]$Port = 0,
  [string]$ProxyUrl = "http://127.0.0.1:7897",
  [string]$NoProxy = "localhost,127.0.0.1,::1",
  [switch]$DisableExplicitProxy
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$tunnelProfiles = @{
  frontend = @{
    DisplayName = "NicoSite frontend (legacy root-domain rollback)"
    Hostname = "niko000o.site"
    DefaultProtocol = "https"
    DefaultPort = 3000
    TunnelIdEnv = "CF_FRONTEND_TUNNEL_ID"
    FallbackTunnelIdEnv = "CF_TUNNEL_ID"
    ConfigFileName = "ai-temperate-frontend.yml"
    PidFileName = "cloudflared-frontend.pid.json"
    StopRequestFileName = "frontend.stop.request"
  }
  "frontend-dev" = @{
    DisplayName = "NicoSite frontend development"
    Hostname = "dev.niko000o.site"
    DefaultProtocol = "https"
    DefaultPort = 3000
    TunnelIdEnv = "CF_FRONTEND_DEV_TUNNEL_ID"
    FallbackTunnelIdEnv = ""
    ConfigFileName = "ai-temperate-frontend-dev.yml"
    PidFileName = "cloudflared-frontend-dev.pid.json"
    StopRequestFileName = "frontend-dev.stop.request"
  }
  admin = @{
    DisplayName = "NicoSite admin"
    Hostname = "admin.niko000o.site"
    DefaultProtocol = "https"
    DefaultPort = 3001
    TunnelIdEnv = "CF_ADMIN_TUNNEL_ID"
    FallbackTunnelIdEnv = ""
    ConfigFileName = "ai-temperate-admin.yml"
    PidFileName = "cloudflared-admin.pid.json"
    StopRequestFileName = "admin.stop.request"
  }
  api = @{
    DisplayName = "NicoSite API"
    Hostname = "api.niko000o.site"
    DefaultProtocol = "https"
    DefaultPort = 6655
    TunnelIdEnv = "CF_API_TUNNEL_ID"
    FallbackTunnelIdEnv = ""
    ConfigFileName = "ai-temperate-api.yml"
    PidFileName = "cloudflared-api.pid.json"
    StopRequestFileName = "api.stop.request"
  }
}

function Resolve-CloudflaredPath {
  $candidates = [System.Collections.Generic.List[string]]::new()
  $command = Get-Command cloudflared -ErrorAction SilentlyContinue
  if ($null -ne $command -and -not [string]::IsNullOrWhiteSpace($command.Source)) {
    $candidates.Add($command.Source)
  }
  $candidates.Add("$env:ProgramFiles\cloudflared\cloudflared.exe")
  $candidates.Add("${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe")

  foreach ($candidate in $candidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
      return (Resolve-Path -LiteralPath $candidate).Path
    }
  }
  return $null
}

function Get-ConfiguredEnvironmentValue {
  param([Parameter(Mandatory = $true)][string]$Name)

  $processValue = [Environment]::GetEnvironmentVariable($Name, "Process")
  if (-not [string]::IsNullOrWhiteSpace($processValue)) {
    return $processValue.Trim()
  }

  $userValue = [Environment]::GetEnvironmentVariable($Name, "User")
  if (-not [string]::IsNullOrWhiteSpace($userValue)) {
    return $userValue.Trim()
  }

  return ""
}

function Read-TunnelProfile {
  while ($true) {
    $value = (Read-Host "Tunnel profile (frontend/frontend-dev/admin/api)").Trim().ToLowerInvariant()
    if ($value -in @("frontend", "frontend-dev", "admin", "api")) {
      return $value
    }
    Write-Host "Invalid profile. Please enter frontend, frontend-dev, admin, or api." -ForegroundColor Yellow
  }
}

function Read-OriginProtocol {
  param([Parameter(Mandatory = $true)][string]$DefaultProtocol)

  while ($true) {
    $value = (Read-Host "Origin protocol (http/https, default $DefaultProtocol)").Trim().ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($value)) {
      return $DefaultProtocol
    }
    if ($value -in @("http", "https")) {
      return $value
    }
    Write-Host "Invalid protocol. Please enter http or https." -ForegroundColor Yellow
  }
}

function Read-OriginPort {
  param([Parameter(Mandatory = $true)][int]$DefaultPort)

  while ($true) {
    $raw = (Read-Host "Local origin port (default $DefaultPort)").Trim()
    if ([string]::IsNullOrWhiteSpace($raw)) {
      return $DefaultPort
    }
    if ($raw -notmatch '^\d+$') {
      Write-Host "Port must contain digits only." -ForegroundColor Yellow
      continue
    }

    $parsedPort = 0
    if (-not [int]::TryParse($raw, [ref]$parsedPort)) {
      Write-Host "Port must be an integer between 1 and 65535." -ForegroundColor Yellow
      continue
    }
    if ($parsedPort -ge 1 -and $parsedPort -le 65535) {
      return $parsedPort
    }
    Write-Host "Port must be an integer between 1 and 65535." -ForegroundColor Yellow
  }
}

function Resolve-ExplicitProxyUrl {
  param(
    [string]$Value,
    [bool]$Disabled
  )

  if ($Disabled) {
    return ""
  }

  $trimmedValue = if ($null -eq $Value) { "" } else { $Value.Trim() }
  if ([string]::IsNullOrWhiteSpace($trimmedValue)) {
    return ""
  }

  $proxyUri = [uri]$null
  if (-not [uri]::TryCreate($trimmedValue, [UriKind]::Absolute, [ref]$proxyUri)) {
    throw "ProxyUrl must be an absolute local HTTP proxy URL, for example http://127.0.0.1:7897."
  }

  if ($proxyUri.Scheme -ne "http") {
    throw "ProxyUrl must use http because Clash mixed-port exposes an HTTP CONNECT proxy."
  }

  if ($proxyUri.Port -lt 1 -or $proxyUri.Port -gt 65535) {
    throw "ProxyUrl must include a port between 1 and 65535."
  }

  $proxyHost = $proxyUri.Host.ToLowerInvariant()
  $allowedHosts = @("127.0.0.1", "localhost", "::1")
  if ($proxyHost -notin $allowedHosts) {
    throw "ProxyUrl must point to a local proxy host: 127.0.0.1, localhost, or ::1."
  }

  if ($proxyUri.AbsolutePath -ne "/" -or
      -not [string]::IsNullOrWhiteSpace($proxyUri.Query) -or
      -not [string]::IsNullOrWhiteSpace($proxyUri.Fragment)) {
    throw "ProxyUrl must not include path, query, or fragment parts."
  }

  $normalizedHost = if ($proxyHost -eq "::1") { "[::1]" } else { $proxyHost }
  return "http://${normalizedHost}:$($proxyUri.Port)"
}

function Remove-StalePidFile {
  param(
    [string]$PidFilePath,
    [string]$DisplayName
  )

  if (-not (Test-Path -LiteralPath $PidFilePath)) {
    return
  }

  try {
    $state = Get-Content -Raw -Encoding utf8 -LiteralPath $PidFilePath | ConvertFrom-Json
    $process = Get-Process -Id ([int]$state.processId) -ErrorAction SilentlyContinue
    if ($null -ne $process -and $process.ProcessName -eq "cloudflared") {
      throw "$DisplayName Cloudflare tunnel is already running. PID=$($process.Id)."
    }
  } catch {
    if ($_.Exception.Message -like "*Cloudflare tunnel is already running*") {
      throw
    }
  }

  Remove-Item -LiteralPath $PidFilePath -Force
}

try {
  if ([string]::IsNullOrWhiteSpace($Profile)) {
    $Profile = Read-TunnelProfile
  }
  $Profile = $Profile.ToLowerInvariant()
  $profileSpec = $tunnelProfiles[$Profile]

  if ([string]::IsNullOrWhiteSpace($Protocol)) {
    $Protocol = Read-OriginProtocol -DefaultProtocol $profileSpec.DefaultProtocol
  }
  if ($Port -eq 0) {
    $Port = Read-OriginPort -DefaultPort ([int]$profileSpec.DefaultPort)
  }
  $explicitProxyUrl = Resolve-ExplicitProxyUrl -Value $ProxyUrl -Disabled $DisableExplicitProxy.IsPresent
  $normalizedNoProxy = if ([string]::IsNullOrWhiteSpace($NoProxy)) {
    "localhost,127.0.0.1,::1"
  } else {
    $NoProxy.Trim()
  }

  $tunnelId = Get-ConfiguredEnvironmentValue -Name $profileSpec.TunnelIdEnv
  if ([string]::IsNullOrWhiteSpace($tunnelId) -and
      -not [string]::IsNullOrWhiteSpace($profileSpec.FallbackTunnelIdEnv)) {
    $tunnelId = Get-ConfiguredEnvironmentValue -Name $profileSpec.FallbackTunnelIdEnv
  }

  $parsedTunnelId = [guid]::Empty
  if ([string]::IsNullOrWhiteSpace($tunnelId) -or
      -not [guid]::TryParse($tunnelId.Trim(), [ref]$parsedTunnelId)) {
    throw "Valid $($profileSpec.TunnelIdEnv) environment variable is not set."
  }
  $normalizedTunnelId = $parsedTunnelId.ToString()

  $cloudflaredPath = Resolve-CloudflaredPath
  if ([string]::IsNullOrWhiteSpace($cloudflaredPath)) {
    throw "cloudflared.exe was not found. Install cloudflared or add it to PATH."
  }

  $cloudflaredDirectory = Join-Path $env:USERPROFILE ".cloudflared"
  $configPath = Join-Path $cloudflaredDirectory $profileSpec.ConfigFileName
  $credentialsFile = Join-Path $cloudflaredDirectory "$normalizedTunnelId.json"
  if (-not (Test-Path -LiteralPath $credentialsFile)) {
    throw "Cloudflare tunnel credentials file not found: $credentialsFile"
  }

  $runtimeDirectory = Join-Path $env:LOCALAPPDATA "ai-temperate\cloudflare"
  New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
  $pidFilePath = Join-Path $runtimeDirectory $profileSpec.PidFileName
  $stopRequestPath = Join-Path $runtimeDirectory $profileSpec.StopRequestFileName
  Remove-StalePidFile -PidFilePath $pidFilePath -DisplayName $profileSpec.DisplayName
  if (Test-Path -LiteralPath $stopRequestPath) {
    Remove-Item -LiteralPath $stopRequestPath -Force
  }

  $certificatePath = Join-Path $env:USERPROFILE ".ai-temperate\certs\local-https.pem"
  if ($Protocol -eq "https" -and -not (Test-Path -LiteralPath $certificatePath)) {
    throw "HTTPS origin certificate not found: $certificatePath"
  }

  & "$PSScriptRoot\update-cloudflare-config.ps1" `
    -ConfigPath $configPath `
    -TunnelId $normalizedTunnelId `
    -CredentialsFile $credentialsFile `
    -Hostname $profileSpec.Hostname `
    -Protocol $Protocol `
    -Port $Port `
    -CloudflaredPath $cloudflaredPath `
    -CertificatePath $certificatePath

  Write-Host ""
  Write-Host "============================================================" -ForegroundColor DarkGray
  Write-Host "Starting $($profileSpec.DisplayName) Cloudflare tunnel" -ForegroundColor Green
  Write-Host "Public hostname: https://$($profileSpec.Hostname)"
  Write-Host "Origin: ${Protocol}://localhost:${Port}"
  if ([string]::IsNullOrWhiteSpace($explicitProxyUrl)) {
    Write-Host "Cloudflared proxy: disabled"
  } else {
    Write-Host "Cloudflared proxy: $explicitProxyUrl"
    Write-Host "Cloudflared no-proxy: $normalizedNoProxy"
  }
  Write-Host "Config: $configPath"
  Write-Host "Press Ctrl+C to stop this profile, or run the matching stop-cloudflare-*.bat in this folder."
  Write-Host "============================================================" -ForegroundColor DarkGray
  Write-Host ""

  & "$PSScriptRoot\cloudflare-ip-guard.ps1" `
    -CloudflaredPath $cloudflaredPath `
    -TunnelId $normalizedTunnelId `
    -ConfigPath $configPath `
    -PidFilePath $pidFilePath `
    -StopRequestPath $stopRequestPath `
    -ProxyUrl $explicitProxyUrl `
    -NoProxy $normalizedNoProxy

  $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } else { 0 }
  if ($exitCode -eq 100) {
    Write-Host "cloudflared egress network changed, so the tunnel was stopped." -ForegroundColor Yellow
  } elseif ($exitCode -ne 0) {
    Write-Host "cloudflared exited abnormally. Exit code: $exitCode" -ForegroundColor Red
  }
  exit $exitCode
} catch {
  Write-Host "Cloudflare tunnel start failed: $($_.Exception.Message)" -ForegroundColor Red
  exit 1
}
