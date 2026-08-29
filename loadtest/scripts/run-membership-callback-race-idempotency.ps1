[CmdletBinding()]
param([ValidateSet('loadtest-realtime')][string]$Mode='loadtest-realtime',[int]$Threads=8,[string]$HostName='localhost',[int]$Port=6655,[string]$Protocol='http',[string]$OrderId='', [string]$Money='', [switch]$Cleanup)
foreach ($concurrency in @(1, 10, 50, 100, 500)) {
    & (Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1') `
        -Scenario 'membership-callback-race-idempotency' `
        -Jmx 'loadtest/jmeter/membership-callback-race-idempotency.jmx' `
        -Mode $Mode `
        -Threads $Threads `
        -Concurrency $concurrency `
        -HostName $HostName `
        -Port $Port `
        -Protocol $Protocol `
        -OrderId $OrderId `
        -Money $Money `
        -Cleanup:$Cleanup
}
