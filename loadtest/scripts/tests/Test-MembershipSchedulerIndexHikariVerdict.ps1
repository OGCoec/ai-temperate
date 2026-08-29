[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot '..\MembershipSchedulerIndexHikariEvidence.psm1'
Import-Module $modulePath -Force
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'membership-special-verdict-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    $latency = New-MembershipCallbackLatencySummary -Values @(
        100D, 500D, 1000D, 4999D, 5000D, 7999D, 8000D, 9999D, 10000D)
    if ($latency.count -ne 9 -or $latency.lessThan1SecondCount -ne 2 -or
            $latency.from1To5SecondsCount -ne 2 -or
            $latency.from5To8SecondsCount -ne 2 -or
            $latency.from8To10SecondsCount -ne 2 -or
            $latency.atLeast10SecondsCount -ne 1 -or
            $latency.maximumMs -ne 10000D) {
        throw 'Callback latency percentile or bucket contract is incorrect.'
    }

    $goodExplainPath = Join-Path $temporaryRoot 'good-explain.json'
    @(
        [ordered]@{
            Plan = [ordered]@{
                'Node Type' = 'Limit'
                Plans = @(
                    [ordered]@{
                        'Node Type' = 'Index Scan'
                        'Index Name' = 'idx_membership_order_latest_paid'
                    })
            }
        }) | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $goodExplainPath -Encoding UTF8
    $goodExplain = Test-MembershipLatestPaidExplain -Path $goodExplainPath
    if ($goodExplain.verdict -ne 'PASS' -or
            $goodExplain.hasSort -or $goodExplain.hasSeqScan) {
        throw 'Correct latest-paid plan was not accepted.'
    }
    $badExplainPath = Join-Path $temporaryRoot 'bad-explain.json'
    @(
        [ordered]@{
            Plan = [ordered]@{
                'Node Type' = 'Sort'
                Plans = @([ordered]@{ 'Node Type' = 'Seq Scan' })
            }
        }) | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $badExplainPath -Encoding UTF8
    if ((Test-MembershipLatestPaidExplain -Path $badExplainPath).verdict -ne 'FAIL') {
        throw 'Sort and Seq Scan plan was not rejected.'
    }

    function New-HikariRows([int[]] $Pending, [int] $Total, [double] $Timeout) {
        return @($Pending | ForEach-Object {
            [pscustomobject]@{
                poolAvailable = 'True'
                configuredMaximumPoolSize = '256'
                configuredMinimumIdle = '8'
                totalConnections = [string]$Total
                pendingThreads = [string]$_
                timeoutCount = [string]$Timeout
                acquireP99Seconds = '0.02'
            }
        })
    }
    $acceptable = Get-MembershipHikariSpecialVerdict `
        -Rows (New-HikariRows -Pending @(0,0,0,0,0,0,0,0,0,0) -Total 256 -Timeout 0) `
        -PostgresRows @([pscustomobject]@{ totalConnections = '383' }) `
        -DatabaseP99Ms 100 `
        -ExpectedMaximumPoolSize 256 `
        -ExpectedMinimumIdle 8 `
        -PostgresMaxConnections 384
    if ($acceptable.verdict -ne '可接受') {
        throw 'Healthy Hikari evidence was not classified as acceptable.'
    }
    $nonoptimalRows = New-HikariRows `
        -Pending @(1,1,1,1,1,1,1,1,1,1) -Total 256 -Timeout 0
    foreach ($row in $nonoptimalRows) { $row.acquireP99Seconds = '0.08' }
    $nonoptimal = Get-MembershipHikariSpecialVerdict `
        -Rows $nonoptimalRows `
        -DatabaseP99Ms 100 `
        -ExpectedMaximumPoolSize 256 `
        -ExpectedMinimumIdle 8 `
        -PostgresMaxConnections 384
    if ($nonoptimal.verdict -ne '可运行但非最优') {
        throw 'Sustained Hikari pending was not classified as nonoptimal.'
    }
    $unacceptable = Get-MembershipHikariSpecialVerdict `
        -Rows (New-HikariRows -Pending @(0) -Total 256 -Timeout 0) `
        -PostgresRows @([pscustomobject]@{ totalConnections = '384' }) `
        -DatabaseP99Ms 100 `
        -ExpectedMaximumPoolSize 256 `
        -ExpectedMinimumIdle 8 `
        -PostgresMaxConnections 384
    if ($unacceptable.verdict -ne '不可接受') {
        throw 'PostgreSQL hard connection limit was not rejected.'
    }

    $queueRows = @(
        [pscustomobject]@{
            sampledAt = '2026-08-24T20:00:00.000Z'; segment = 'E-P1'
            callbackReadySize = '2500'; callbackProcessingSize = '100'
            dirtySize = '2600'; dirtyProcessingSize = '100'
        },
        [pscustomobject]@{
            sampledAt = '2026-08-24T20:00:00.500Z'; segment = 'E-P1'
            callbackReadySize = '1000'; callbackProcessingSize = '100'
            dirtySize = '1000'; dirtyProcessingSize = '100'
        },
        [pscustomobject]@{
            sampledAt = '2026-08-24T20:00:01.000Z'; segment = 'E-P1'
            callbackReadySize = '0'; callbackProcessingSize = '0'
            dirtySize = '0'; dirtyProcessingSize = '0'
        })
    $runtimeRows = @([pscustomobject]@{
        callbackMaximumBatches = '50'
        callbackMaximumClaimedItems = '5000'
        callbackLastThreadName = 'membership-payment-callback-1'
        orderPersistMaximumBatches = '50'
        orderPersistMaximumClaimedItems = '5000'
        orderPersistLastThreadName = 'membership-payment-order-persist-1'
    })
    $schedulerLatency = New-MembershipCallbackLatencySummary -Values @(100D, 7000D)
    $scheduler = Get-MembershipSchedulerSpecialVerdict `
        -ThreadDumpText @'
"membership-payment-callback-1"
"membership-payment-order-persist-1"
'@ `
        -QueueRows $queueRows `
        -RuntimeRows $runtimeRows `
        -LatencySummary $schedulerLatency
    if ($scheduler.verdict -ne 'PASS' -or
            -not $scheduler.callbackCrossedOldBoundary -or
            -not $scheduler.orderPersistCrossedOldBoundary) {
        throw 'Valid natural scheduler evidence was not accepted.'
    }

    $beforePath = Join-Path $temporaryRoot 'before.txt'
    $afterPath = Join-Path $temporaryRoot 'after.txt'
    'captured | idx_membership_order_latest_paid | 10 | 10 | 10' |
        Set-Content -LiteralPath $beforePath -Encoding UTF8
    'captured | idx_membership_order_latest_paid | 12 | 12 | 12' |
        Set-Content -LiteralPath $afterPath -Encoding UTF8
    if ((Get-MembershipIndexScanCount -Path $afterPath) -ne 12L) {
        throw 'Index scan count parser returned the wrong value.'
    }
} finally {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
}

Write-Output 'PASS: scheduler, index and Hikari verdict contracts are stable.'
