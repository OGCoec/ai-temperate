[CmdletBinding()]
param([ValidateSet('loadtest-realtime')][string]$Mode='loadtest-realtime',[int]$Threads=8,[string]$HostName='localhost',[int]$Port=6655,[string]$Protocol='http',[switch]$Cleanup)
& (Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1') -Scenario 'membership-auth-boundary' -Jmx 'loadtest/jmeter/membership-auth-boundary.jmx' -Mode $Mode -Threads $Threads -HostName $HostName -Port $Port -Protocol $Protocol -Cleanup:$Cleanup
