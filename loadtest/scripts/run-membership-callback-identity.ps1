[CmdletBinding()]
param(
    [ValidateSet('loadtest-realtime')]
    [string] $Mode = 'loadtest-realtime',
    [int] $Threads = 5,
    [ValidateSet(1, 10, 50)]
    [int] $Concurrency = 10,
    [string] $HostName = 'localhost',
    [int] $Port = 6655,
    [string] $Protocol = 'http',
    [switch] $Cleanup
)

& (Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1') `
    -Scenario 'membership-callback-identity' `
    -Jmx 'loadtest/jmeter/membership-callback-race-idempotency.jmx' `
    -Mode $Mode `
    -Threads $Threads `
    -Concurrency $Concurrency `
    -HostName $HostName `
    -Port $Port `
    -Protocol $Protocol `
    -Cleanup:$Cleanup
