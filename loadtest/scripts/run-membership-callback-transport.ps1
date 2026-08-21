[CmdletBinding()]
param([ValidateSet('loadtest-realtime')][string]$Mode='loadtest-realtime',[int]$Threads=15,[string]$HostName='localhost',[int]$Port=8080,[string]$Protocol='http',[string]$OrderId='', [string]$Money='', [switch]$Cleanup)
& (Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1') -Scenario 'membership-callback-transport' -Jmx 'loadtest/jmeter/membership-callback-transport.jmx' -Mode $Mode -Threads $Threads -HostName $HostName -Port $Port -Protocol $Protocol -OrderId $OrderId -Money $Money -Cleanup:$Cleanup
