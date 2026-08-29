[CmdletBinding()]
param(
    [ValidateSet('loadtest-realtime')]
    [string] $Mode = 'loadtest-realtime',
    [int] $Threads = 12,
    [string] $HostName = 'localhost',
    [int] $Port = 6655,
    [string] $Protocol = 'http',
    [switch] $Cleanup
)

$root = Resolve-Path (Join-Path $PSScriptRoot '../..')
& (Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1') `
    -Scenario 'membership-long-observation' `
    -Jmx (Join-Path $root 'loadtest/jmeter/membership-order-state-machine.jmx') `
    -Mode $Mode `
    -Threads $Threads `
    -HostName $HostName `
    -Port $Port `
    -Protocol $Protocol `
    -Cleanup:$Cleanup
