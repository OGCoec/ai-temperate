[CmdletBinding()]
param([ValidateSet('loadtest-realtime')][string]$Mode='loadtest-realtime',[int]$Threads=6,[string]$HostName='localhost',[int]$Port=8080,[string]$Protocol='http',[switch]$Cleanup)
& (Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1') -Scenario 'membership-rabbit-state-timing' -Jmx 'loadtest/jmeter/membership-rabbit-state-timing.jmx' -Mode $Mode -Threads $Threads -HostName $HostName -Port $Port -Protocol $Protocol -Cleanup:$Cleanup
