[CmdletBinding()]
param([ValidateSet('loadtest-realtime')][string]$Mode='loadtest-realtime',[int]$Threads=6,[string]$HostName='localhost',[int]$Port=6655,[string]$Protocol='http',[switch]$Cleanup)
& (Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1') -Scenario 'membership-persistence-batch' -Jmx 'loadtest/jmeter/membership-persistence-batch.jmx' -Mode $Mode -Threads $Threads -HostName $HostName -Port $Port -Protocol $Protocol -Cleanup:$Cleanup
