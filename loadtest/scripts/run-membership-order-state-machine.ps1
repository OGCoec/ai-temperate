[CmdletBinding()]
param([ValidateSet('loadtest-realtime')][string]$Mode='loadtest-realtime',[int]$Threads=25,[string]$HostName='localhost',[int]$Port=8080,[string]$Protocol='http',[switch]$Cleanup)
& (Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1') -Scenario 'membership-order-state-machine' -Jmx 'loadtest/jmeter/membership-order-state-machine.jmx' -Mode $Mode -Threads $Threads -HostName $HostName -Port $Port -Protocol $Protocol -Cleanup:$Cleanup
