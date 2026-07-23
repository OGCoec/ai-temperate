[CmdletBinding(SupportsShouldProcess = $true)]
param(
  [ValidateSet("Apply", "Remove", "Status")]
  [string]$Action = "Status"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$interfaceAlias = "ait-origin"
$listenAddress = "10.66.0.2"
$azureAddress = "10.66.0.1"
$loopbackAddress = "127.0.0.1"
$allowedPorts = @(3000, 3001, 6655)
$blockedTcpPortRanges = @("1-2999", "3002-6654", "6656-65535")
$firewallGroupName = "ai-temperate WireGuard origin"
$firewallAllowRuleName = "ai-temperate WireGuard origin allowlist"
$firewallTcpBlockRuleName = "ai-temperate WireGuard origin TCP complement block"
$firewallUdpBlockRuleName = "ai-temperate WireGuard origin UDP block"

function Assert-Administrator {
  $principal = [Security.Principal.WindowsPrincipal]::new(
    [Security.Principal.WindowsIdentity]::GetCurrent())
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Apply and Remove must run from an elevated PowerShell session."
  }
}

function Remove-ManagedPortProxyRules {
  foreach ($port in $allowedPorts) {
    & netsh interface portproxy delete v4tov4 `
      listenaddress=$listenAddress `
      listenport=$port | Out-Null
  }
}

function Remove-ManagedFirewallRules {
  Get-NetFirewallRule -Group $firewallGroupName -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule
}

function Show-ManagedStatus {
  Write-Host "Allowed application ports: $($allowedPorts -join ', ')"
  Write-Host "WireGuard origin path: $azureAddress -> $listenAddress"
  Write-Host "TCP Port Proxy:"
  & netsh interface portproxy show v4tov4
  Write-Host "Windows Firewall rules:"
  Get-NetFirewallRule -Group $firewallGroupName -ErrorAction SilentlyContinue |
    Select-Object DisplayName, Enabled, Direction, Action, Profile
}

switch ($Action) {
  "Status" {
    Show-ManagedStatus
  }
  "Apply" {
    Assert-Administrator
    $wireGuardAddress = Get-NetIPAddress `
      -InterfaceAlias $interfaceAlias `
      -AddressFamily IPv4 `
      -IPAddress $listenAddress `
      -ErrorAction SilentlyContinue
    if ($null -eq $wireGuardAddress) {
      throw "WireGuard interface $interfaceAlias does not have address $listenAddress."
    }

    if ($PSCmdlet.ShouldProcess("$listenAddress TCP $($allowedPorts -join ',')", "Configure restricted WireGuard origin forwarding")) {
      $ipHelper = Get-Service -Name iphlpsvc -ErrorAction Stop
      if ($ipHelper.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Running) {
        Start-Service -Name iphlpsvc
        $ipHelper.WaitForStatus(
          [System.ServiceProcess.ServiceControllerStatus]::Running,
          [TimeSpan]::FromSeconds(10))
      }

      Remove-ManagedPortProxyRules
      foreach ($port in $allowedPorts) {
        & netsh interface portproxy add v4tov4 `
          listenaddress=$listenAddress `
          listenport=$port `
          connectaddress=$loopbackAddress `
          connectport=$port | Out-Null
        if ($LASTEXITCODE -ne 0) {
          throw "Failed to create Port Proxy for port $port; exit code: $LASTEXITCODE."
        }
      }

      Get-NetFirewallRule -DisplayName $firewallAllowRuleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule
      Remove-ManagedFirewallRules
      New-NetFirewallRule `
        -DisplayName $firewallAllowRuleName `
        -Group $firewallGroupName `
        -Direction Inbound `
        -Action Allow `
        -Protocol TCP `
        -LocalAddress $listenAddress `
        -RemoteAddress $azureAddress `
        -LocalPort $allowedPorts `
        -InterfaceAlias $interfaceAlias `
        -Profile Any | Out-Null
      New-NetFirewallRule `
        -DisplayName $firewallTcpBlockRuleName `
        -Group $firewallGroupName `
        -Direction Inbound `
        -Action Block `
        -Protocol TCP `
        -LocalAddress $listenAddress `
        -RemoteAddress $azureAddress `
        -LocalPort $blockedTcpPortRanges `
        -InterfaceAlias $interfaceAlias `
        -Profile Any | Out-Null
      New-NetFirewallRule `
        -DisplayName $firewallUdpBlockRuleName `
        -Group $firewallGroupName `
        -Direction Inbound `
        -Action Block `
        -Protocol UDP `
        -LocalAddress $listenAddress `
        -RemoteAddress $azureAddress `
        -LocalPort Any `
        -InterfaceAlias $interfaceAlias `
        -Profile Any | Out-Null
    }
    Show-ManagedStatus
  }
  "Remove" {
    Assert-Administrator
    if ($PSCmdlet.ShouldProcess("$listenAddress TCP $($allowedPorts -join ',')", "Remove WireGuard origin forwarding")) {
      Remove-ManagedPortProxyRules
      Get-NetFirewallRule -DisplayName $firewallAllowRuleName -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule
      Remove-ManagedFirewallRules
    }
    Show-ManagedStatus
  }
}
