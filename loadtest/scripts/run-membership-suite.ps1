[CmdletBinding()]
param(
    [ValidateSet('loadtest-realtime')][string]$Mode='loadtest-realtime',
    [int]$Threads=4,
    [string]$HostName='localhost',
    [int]$Port=8080,
    [string]$Protocol='http',
    [switch]$Cleanup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$scripts = @(
    'run-membership-auth-boundary.ps1',
    'run-membership-order-state-machine.ps1',
    'run-membership-callback-transport.ps1',
    'run-membership-callback-race-idempotency.ps1',
    'run-membership-rabbit-state-timing.ps1',
    'run-membership-persistence-batch.ps1',
    'run-membership-recovery-terminal-cleanup.ps1'
)
$results = [System.Collections.Generic.List[object]]::new()
foreach ($scriptName in $scripts) {
    $path = Join-Path $PSScriptRoot $scriptName
    $minimumThreads = switch ($scriptName) {
        'run-membership-auth-boundary.ps1' { 8 }
        'run-membership-order-state-machine.ps1' { 25 }
        'run-membership-callback-transport.ps1' { 15 }
        'run-membership-callback-race-idempotency.ps1' { 8 }
        'run-membership-rabbit-state-timing.ps1' { 6 }
        'run-membership-persistence-batch.ps1' { 6 }
        'run-membership-recovery-terminal-cleanup.ps1' { 7 }
        default { throw "Unknown membership Runner: $scriptName" }
    }
    $scenarioThreads = [Math]::Max($Threads, $minimumThreads)
    try {
        & $path -Mode $Mode -Threads $scenarioThreads -HostName $HostName -Port $Port -Protocol $Protocol -Cleanup:$Cleanup
        $results.Add([pscustomobject]@{ scenario = $scriptName; verdict = 'PASS' })
    } catch {
        $results.Add([pscustomobject]@{ scenario = $scriptName; verdict = 'FAIL'; error = $_.Exception.Message })
        $results | ConvertTo-Json -Depth 5
        exit 1
    }
}
$results | ConvertTo-Json -Depth 5
Write-Host 'All seven membership payment loadtest scenarios passed.'
