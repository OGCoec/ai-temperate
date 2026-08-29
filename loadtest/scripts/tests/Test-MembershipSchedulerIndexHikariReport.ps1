[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$reportScript = Join-Path $PSScriptRoot '..\New-MembershipSchedulerIndexHikariReport.ps1'
if (-not (Test-Path -LiteralPath $reportScript -PathType Leaf)) {
    throw "Special report script is missing: $reportScript"
}
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'membership-special-report-' + [guid]::NewGuid().ToString('N'))
$groups = @('E-P1','E-PR','E-A1','E-AR','H-P1','H-PR','H-A1','H-AR')
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    [ordered]@{
        runId = 'fixture-run'
        groups = $groups
        connectionContract = [ordered]@{
            postgresMaxConnections = 384
            hikariMaximumPoolSize = 256
            hikariMinimumIdle = 8
            maximumNavicatConnections = 8
        }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (
        Join-Path $temporaryRoot 'run-manifest.json') -Encoding UTF8
    $index = 0
    foreach ($group in $groups) {
        $directory = Join-Path $temporaryRoot $group
        New-Item -ItemType Directory -Path $directory | Out-Null
        $received = [datetimeoffset]::Parse('2026-08-24T20:00:00Z').AddSeconds($index)
        [pscustomobject][ordered]@{
            run_id = 'fixture-run'
            group_code = $group
            order_id = ('A' * 21) + [string]($index % 10)
            received_at = $received.ToString('O')
            callback_resolved_at = $received.AddMilliseconds(500 + $index).ToString('O')
            failure = ''
        } | Export-Csv -LiteralPath (Join-Path $directory 'server-time-verdict.csv') `
            -NoTypeInformation -Encoding UTF8
        $index += 1
    }
    @(
        [pscustomobject][ordered]@{
            sampledAt='2026-08-24T20:00:00.000Z';segment='E-P1'
            callbackReadySize=2500;callbackProcessingSize=100
            dirtySize=2500;dirtyProcessingSize=100
            rabbitPaymentReady=0;rabbitPaymentUnacked=0
            rabbitClosingReady=0;rabbitClosingUnacked=0
        },
        [pscustomobject][ordered]@{
            sampledAt='2026-08-24T20:00:00.500Z';segment='COMPLETE'
            callbackReadySize=0;callbackProcessingSize=0
            dirtySize=0;dirtyProcessingSize=0
            rabbitPaymentReady=0;rabbitPaymentUnacked=0
            rabbitClosingReady=0;rabbitClosingUnacked=0
        }) | Export-Csv -LiteralPath (Join-Path $temporaryRoot 'scheduler-queue-samples.csv') `
        -NoTypeInformation -Encoding UTF8
    @(1..10 | ForEach-Object {
        [pscustomobject][ordered]@{
            sampledAt="2026-08-24T20:00:$('{0:D2}' -f $_).000Z"
            segment='E-P1';poolAvailable=$true;poolName='membershipPool'
            configuredMaximumPoolSize=256;configuredMinimumIdle=8
            totalConnections=256;activeConnections=20;idleConnections=236
            pendingThreads=0;timeoutCount=0;acquireCount=100
            acquireTotalSeconds=1;acquireMaximumSeconds=0.03
            acquireP95Seconds=0.01;acquireP99Seconds=0.02
            usageCount=100;usageTotalSeconds=5;usageMaximumSeconds=0.1
            usageP95Seconds=0.05;usageP99Seconds=0.08
            callbackRunCount=4;callbackLastBatches=50
            callbackLastClaimedItems=5000;callbackMaximumBatches=50
            callbackMaximumClaimedItems=5000;callbackLastOutcome='capacity'
            callbackLastDurationNanos=1;callbackLastThreadName='membership-payment-callback-1'
            callbackLastCompletedAtEpochMillis=1
            orderPersistRunCount=4;orderPersistLastBatches=50
            orderPersistLastClaimedItems=5000;orderPersistMaximumBatches=50
            orderPersistMaximumClaimedItems=5000;orderPersistLastOutcome='capacity'
            orderPersistLastDurationNanos=1
            orderPersistLastThreadName='membership-payment-order-persist-1'
            orderPersistLastCompletedAtEpochMillis=1
        }
    }) | Export-Csv -LiteralPath (Join-Path $temporaryRoot 'hikari-runtime-samples.csv') `
        -NoTypeInformation -Encoding UTF8
    @(
        [pscustomobject][ordered]@{
            sampledAt='2026-08-24T20:00:01.000Z';segment='E-P1'
            accepting=$true;configuredBatchSize=64;configuredLaneCount=6
            maximumInflight=384;inflight=17;availablePermits=367
            lane0QueueDepth=2;lane1QueueDepth=3;lane2QueueDepth=4
            lane3QueueDepth=3;lane4QueueDepth=2;lane5QueueDepth=3
            lane0FullRestoreQueueDepth=1;lane1FullRestoreQueueDepth=2
            lane2FullRestoreQueueDepth=3;lane3FullRestoreQueueDepth=2
            lane4FullRestoreQueueDepth=1;lane5FullRestoreQueueDepth=2
            lane0PaymentAttemptPatchQueueDepth=1;lane1PaymentAttemptPatchQueueDepth=1
            lane2PaymentAttemptPatchQueueDepth=1;lane3PaymentAttemptPatchQueueDepth=1
            lane4PaymentAttemptPatchQueueDepth=1;lane5PaymentAttemptPatchQueueDepth=1
        }
    ) | Export-Csv -LiteralPath (
        Join-Path $temporaryRoot 'redis-write-runtime-samples.csv') `
        -NoTypeInformation -Encoding UTF8
    @(
        [pscustomobject]@{
            sampledAt='2026-08-24T20:00:00.000Z'
            totalConnections=260;activeConnections=40;waitingConnections=2
            navicatTotal=4;navicatActive=1;navicatIdleInTransaction=0
            navicatWriteOrDdl=0
        },
        [pscustomobject]@{
            sampledAt='2026-08-24T20:00:01.000Z'
            totalConnections=259;activeConnections=32;waitingConnections=1
            navicatTotal=4;navicatActive=0;navicatIdleInTransaction=0
            navicatWriteOrDdl=0
        }) | Export-Csv -LiteralPath (
            Join-Path $temporaryRoot 'postgres-connection-samples.csv') `
            -NoTypeInformation -Encoding UTF8
    @'
"membership-payment-callback-1"
"membership-payment-order-persist-1"
'@ | Set-Content -LiteralPath (
        Join-Path $temporaryRoot 'membership-payment-thread-dump.txt') -Encoding UTF8
    @(
        [ordered]@{
            Plan=[ordered]@{
                'Node Type'='Limit'
                Plans=@([ordered]@{
                    'Node Type'='Index Scan'
                    'Index Name'='idx_membership_order_latest_paid'
                })
            }
        }) | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath (
            Join-Path $temporaryRoot 'latest-paid-order-explain.json') -Encoding UTF8
    'captured | idx_membership_order_latest_paid | 10 | 10 | 10' |
        Set-Content -LiteralPath (
            Join-Path $temporaryRoot 'latest-paid-index-before.txt') -Encoding UTF8
    'captured | idx_membership_order_latest_paid | 12 | 12 | 12' |
        Set-Content -LiteralPath (
            Join-Path $temporaryRoot 'latest-paid-index-after.txt') -Encoding UTF8
    [ordered]@{verdict='PASS';httpStatus=200;businessQueryExecuted=$true} |
        ConvertTo-Json | Set-Content -LiteralPath (
            Join-Path $temporaryRoot 'latest-paid-upgrade-probe.json') -Encoding UTF8
    [ordered]@{verdict='PASS';performancePassed=$true} |
        ConvertTo-Json | Set-Content -LiteralPath (
            Join-Path $temporaryRoot 'verdict.json') -Encoding UTF8
    $timingSummary = Join-Path $temporaryRoot 'focused-summary.csv'
    @(
        [pscustomobject]@{operation='ORDER_CREATE';dbP99Ms=100},
        [pscustomobject]@{operation='PAYMENT_ATTEMPT';dbP99Ms=80}) |
        Export-Csv -LiteralPath $timingSummary -NoTypeInformation -Encoding UTF8

    & $reportScript `
        -RunRoot $temporaryRoot `
        -ExpectedRowsPerSegment 1 `
        -FocusedTimingSummaryCsvPath $timingSummary

    $latencySummary = Get-Content -Raw -LiteralPath (
        Join-Path $temporaryRoot 'callback-resolution-latency-summary.json') |
        ConvertFrom-Json
    $verdict = Get-Content -Raw -LiteralPath (
        Join-Path $temporaryRoot 'scheduler-index-hikari-verdict.json') |
        ConvertFrom-Json
    if ([int]$latencySummary.overall.count -ne 8 -or
            $verdict.conclusion -ne '功能与性能均 PASS。' -or
            $verdict.scheduler.verdict -ne 'PASS' -or
            $verdict.latestPaidIndex.verdict -ne 'PASS' -or
            $verdict.hikari.verdict -ne '可接受' -or
            [int]$verdict.redisWrite.pipelineBatchSize -ne 64 -or
            [int]$verdict.redisWrite.laneCount -ne 6 -or
            [int]$verdict.redisWrite.maximumInflight -ne 384) {
        throw 'Special report did not produce the expected complete PASS verdict.'
    }
    $markdown = Get-Content -Raw -LiteralPath (
        Join-Path $temporaryRoot 'scheduler-index-hikari-report.md')
    if ($markdown -notmatch 'Hikari 256' -or
            $markdown -notmatch 'PostgreSQL 384' -or
            $markdown -notmatch 'Redis Pipeline：64' -or
            $markdown -notmatch 'Redis lane：6' -or
            $markdown -notmatch 'Redis 总逻辑在途：384') {
        throw 'Special report does not identify the frozen PostgreSQL/Hikari/Redis contract.'
    }
    foreach ($artifact in @(
            'callback-resolution-latency.csv',
            'callback-resolution-latency-summary.json',
            'scheduler-index-hikari-verdict.json',
            'scheduler-index-hikari-report.md')) {
        if (-not (Test-Path -LiteralPath (Join-Path $temporaryRoot $artifact))) {
            throw "Special report artifact is missing: $artifact"
        }
    }
} finally {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
}

Write-Output 'PASS: scheduler, index and Hikari report artifacts are complete.'
