[CmdletBinding()]
param([ValidateSet('loadtest-realtime')][string]$Mode='loadtest-realtime',[int]$Threads=12,[string]$HostName='localhost',[int]$Port=6655,[string]$Protocol='http',[string]$OrderId='', [switch]$Cleanup)
& (Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1') -Scenario 'membership-recovery-terminal-cleanup' -Jmx 'loadtest/jmeter/membership-recovery-terminal-cleanup.jmx' -Mode $Mode -Threads $Threads -HostName $HostName -Port $Port -Protocol $Protocol -Cleanup:$Cleanup
