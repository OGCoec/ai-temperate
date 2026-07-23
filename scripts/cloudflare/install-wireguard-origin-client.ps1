[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$ServerPublicKey,
  [switch]$Replace
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$tunnelName = "ait-origin"
$wireGuardAddress = "10.66.0.2/30"
$azureEndpoint = "130.131.4.13:51820"
$allowedIp = "10.66.0.1/32"
$mtu = 1420

function Assert-Administrator {
  $principal = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent())
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "This script must run from an elevated PowerShell session."
  }
}

function Assert-WireGuardPublicKey {
  param([Parameter(Mandatory = $true)][string]$Value)

  try {
    $decoded = [Convert]::FromBase64String($Value.Trim())
  } catch {
    throw "ServerPublicKey is not a valid Base64 WireGuard public key."
  }
  if ($decoded.Length -ne 32) {
    throw "ServerPublicKey must decode to exactly 32 bytes."
  }
}

function Set-PrivateDirectoryAcl {
  param([Parameter(Mandatory = $true)][string]$Path)

  $security = [System.Security.AccessControl.DirectorySecurity]::new()
  $security.SetAccessRuleProtection($true, $false)
  $fullControl = [System.Security.AccessControl.FileSystemRights]::FullControl
  $inheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
      [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
  $propagation = [System.Security.AccessControl.PropagationFlags]::None
  foreach ($sidValue in @(
      "S-1-5-18",
      "S-1-5-32-544",
      [Security.Principal.WindowsIdentity]::GetCurrent().User.Value)) {
    $sid = [Security.Principal.SecurityIdentifier]::new($sidValue)
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
      $sid,
      $fullControl,
      $inheritance,
      $propagation,
      [System.Security.AccessControl.AccessControlType]::Allow)
    $security.AddAccessRule($rule)
  }
  [System.IO.Directory]::SetAccessControl($Path, $security)
}

function Set-ReadableDirectoryAcl {
  param([Parameter(Mandatory = $true)][string]$Path)

  $security = [System.Security.AccessControl.DirectorySecurity]::new()
  $security.SetAccessRuleProtection($true, $false)
  $fullControl = [System.Security.AccessControl.FileSystemRights]::FullControl
  $readAndExecute = [System.Security.AccessControl.FileSystemRights]::ReadAndExecute
  $inheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
      [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
  $propagation = [System.Security.AccessControl.PropagationFlags]::None

  foreach ($sidValue in @(
      "S-1-5-18",
      "S-1-5-32-544")) {
    $sid = [Security.Principal.SecurityIdentifier]::new($sidValue)
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
      $sid,
      $fullControl,
      $inheritance,
      $propagation,
      [System.Security.AccessControl.AccessControlType]::Allow)
    $security.AddAccessRule($rule)
  }

  $usersSid = [Security.Principal.SecurityIdentifier]::new("S-1-5-32-545")
  $usersRule = [System.Security.AccessControl.FileSystemAccessRule]::new(
    $usersSid,
    $readAndExecute,
    $inheritance,
    $propagation,
    [System.Security.AccessControl.AccessControlType]::Allow)
  $security.AddAccessRule($usersRule)
  [System.IO.Directory]::SetAccessControl($Path, $security)
}

function Set-PrivateFileAcl {
  param([Parameter(Mandatory = $true)][string]$Path)

  $security = [System.Security.AccessControl.FileSecurity]::new()
  $security.SetAccessRuleProtection($true, $false)
  $fullControl = [System.Security.AccessControl.FileSystemRights]::FullControl
  foreach ($sidValue in @(
      "S-1-5-18",
      "S-1-5-32-544")) {
    $sid = [Security.Principal.SecurityIdentifier]::new($sidValue)
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
      $sid,
      $fullControl,
      [System.Security.AccessControl.AccessControlType]::Allow)
    $security.AddAccessRule($rule)
  }
  [System.IO.File]::SetAccessControl($Path, $security)
}

function Wait-ServiceRemoved {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [int]$TimeoutSeconds = 30
  )

  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $service) {
      return
    }
    $service.Dispose()
    Start-Sleep -Milliseconds 250
  } while ([DateTime]::UtcNow -lt $deadline)

  throw "Service $Name was not fully removed within $TimeoutSeconds seconds."
}

function Wait-ServiceRunning {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [int]$TimeoutSeconds = 30
  )

  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if ($null -ne $service) {
      if ($service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Running) {
        return $service
      }
      if ($service.Status -eq [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
        try {
          Start-Service -Name $Name -ErrorAction Stop
        } catch [Microsoft.PowerShell.Commands.ServiceCommandException] {
          # The installer registers the service asynchronously; retry until SCM exposes it consistently.
        }
      }
      $service.Dispose()
    }
    Start-Sleep -Milliseconds 250
  } while ([DateTime]::UtcNow -lt $deadline)

  throw "Service $Name did not reach the Running state within $TimeoutSeconds seconds."
}

Assert-Administrator
$normalizedServerPublicKey = $ServerPublicKey.Trim()
Assert-WireGuardPublicKey -Value $normalizedServerPublicKey

$wireGuardDirectory = Join-Path $env:ProgramFiles "WireGuard"
$wireGuardExecutable = Join-Path $wireGuardDirectory "wireguard.exe"
$wgExecutable = Join-Path $wireGuardDirectory "wg.exe"
if (-not (Test-Path -LiteralPath $wireGuardExecutable) -or
    -not (Test-Path -LiteralPath $wgExecutable)) {
  throw "Official WireGuard is not installed. Run: winget install --id WireGuard.WireGuard --exact"
}

$serviceName = 'WireGuardTunnel$' + $tunnelName
$existingService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue

$runtimeDirectory = Join-Path $env:ProgramData "ai-temperate\wireguard"
if (-not (Test-Path -LiteralPath $runtimeDirectory)) {
  New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
}
Set-PrivateDirectoryAcl -Path $runtimeDirectory
$protectedConfigPath = Join-Path $runtimeDirectory "$tunnelName.conf"
$publicKeyOutputPath = Join-Path $runtimeDirectory "$tunnelName.pub"

if ($null -ne $existingService -and
    -not $Replace.IsPresent -and
    (Test-Path -LiteralPath $protectedConfigPath)) {
  $existingService = Wait-ServiceRunning -Name $serviceName
  $existingPublicKey = (& $wgExecutable show $tunnelName public-key).Trim()
  if ([string]::IsNullOrWhiteSpace($existingPublicKey)) {
    throw "WireGuard tunnel $tunnelName exists, but its public key cannot be read."
  }
  [System.IO.File]::WriteAllText(
    $publicKeyOutputPath,
    $existingPublicKey + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))
  Set-ReadableDirectoryAcl -Path $runtimeDirectory
  Write-Host "WireGuard tunnel already exists; keeping its configuration: $tunnelName" -ForegroundColor Green
  Write-Host "Client public key (safe to provide to Azure): $existingPublicKey"
  return
}

$clientPrivateKey = $null
try {
  $clientPrivateKey = (& $wgExecutable genkey).Trim()
  if ([string]::IsNullOrWhiteSpace($clientPrivateKey)) {
    throw "WireGuard failed to generate the client private key."
  }
  $clientPublicKey = ($clientPrivateKey | & $wgExecutable pubkey).Trim()
  if ([string]::IsNullOrWhiteSpace($clientPublicKey)) {
    throw "WireGuard failed to generate the client public key."
  }

  $configuration = @"
[Interface]
PrivateKey = $clientPrivateKey
Address = $wireGuardAddress
MTU = $mtu

[Peer]
PublicKey = $normalizedServerPublicKey
AllowedIPs = $allowedIp
Endpoint = $azureEndpoint
PersistentKeepalive = 25
"@
  [System.IO.File]::WriteAllText(
    $protectedConfigPath,
    $configuration,
    [System.Text.UTF8Encoding]::new($false))
  Set-PrivateFileAcl -Path $protectedConfigPath

  if ($null -ne $existingService) {
    $existingService.Dispose()
    $existingService = $null
    & $wireGuardExecutable /uninstalltunnelservice $tunnelName
    if ($LASTEXITCODE -ne 0) {
      throw "Failed to remove the old WireGuard tunnel; exit code: $LASTEXITCODE."
    }
    Wait-ServiceRemoved -Name $serviceName
  }

  & $wireGuardExecutable /installtunnelservice $protectedConfigPath
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to install the WireGuard tunnel; exit code: $LASTEXITCODE."
  }

  $installedService = Wait-ServiceRunning -Name $serviceName

  [System.IO.File]::WriteAllText(
    $publicKeyOutputPath,
    $clientPublicKey + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))
  Set-ReadableDirectoryAcl -Path $runtimeDirectory
  Write-Host "WireGuard tunnel installed and started: $tunnelName" -ForegroundColor Green
  Write-Host "Client public key (safe to provide to Azure): $clientPublicKey"
} finally {
  $clientPrivateKey = $null
  $configuration = $null
}
