[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot '..\MembershipSchedulerIndexHikariEvidence.psm1'
Import-Module $modulePath -Force

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'membership-runtime-evidence-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    $statePath = Join-Path $temporaryRoot 'soak-state.json'
    [ordered]@{ wave = 'E-P1'; state = 'RUNNING' } |
        ConvertTo-Json |
        Set-Content -LiteralPath $statePath -Encoding UTF8
    $segment = Get-MembershipEvidenceSegment -StatePath $statePath
    if ($segment -ne 'E-P1') { throw 'Sampler did not read the active suite segment.' }

    $redis = [pscustomobject]@{
        callbackReadySize = 2100
        callbackProcessingSize = 100
        dirtySize = 2500
        dirtyProcessingSize = 50
    }
    $rabbit = @(
        [pscustomobject]@{
            name = 'membership.payment.check.queue'
            messages_ready = 12
            messages_unacknowledged = 4
        },
        [pscustomobject]@{
            name = 'membership.closing.check.queue'
            messages_ready = 7
            messages_unacknowledged = 2
        }
    )
    $queueRow = New-MembershipSchedulerQueueSample `
        -SampledAt '2026-08-24T20:00:00.000Z' `
        -Segment $segment `
        -RedisQueues $redis `
        -RabbitQueues $rabbit
    $expectedQueueColumns = @(
        'sampledAt', 'segment', 'callbackReadySize',
        'callbackProcessingSize', 'dirtySize', 'dirtyProcessingSize',
        'rabbitPaymentReady', 'rabbitPaymentUnacked',
        'rabbitClosingReady', 'rabbitClosingUnacked')
    if (($queueRow.PSObject.Properties.Name -join '|') -ne
            ($expectedQueueColumns -join '|')) {
        throw 'Scheduler queue sample columns changed.'
    }
    if ([long]$queueRow.rabbitPaymentReady -ne 12L -or
            [long]$queueRow.rabbitClosingUnacked -ne 2L) {
        throw 'Rabbit queue values were mapped incorrectly.'
    }

    $runtime = [pscustomobject]@{
        capturedAtEpochMillis = 1787623200000
        hikari = [pscustomobject]@{
            poolAvailable = $true
            poolName = 'membershipPool'
            configuredMaximumPoolSize = 96
            configuredMinimumIdle = 8
            totalConnections = 40
            activeConnections = 32
            idleConnections = 8
            pendingThreads = 3
            timeoutCount = 0
            acquire = [pscustomobject]@{
                count = 100
                totalSeconds = 1.5
                maximumSeconds = 0.1
                p95Seconds = 0.02
                p99Seconds = 0.04
            }
            usage = [pscustomobject]@{
                count = 100
                totalSeconds = 8.0
                maximumSeconds = 0.3
                p95Seconds = 0.12
                p99Seconds = 0.2
            }
        }
        callbackWorker = [pscustomobject]@{
            runCount = 4
            lastBatches = 50
            lastClaimedItems = 5000
            maximumBatches = 50
            maximumClaimedItems = 5000
            lastOutcome = 'capacity'
            lastDurationNanos = 1000000
            lastThreadName = 'membership-payment-callback-1'
            lastCompletedAtEpochMillis = 1787623200000
        }
        orderPersistWorker = [pscustomobject]@{
            runCount = 3
            lastBatches = 25
            lastClaimedItems = 2500
            maximumBatches = 25
            maximumClaimedItems = 2500
            lastOutcome = 'drained'
            lastDurationNanos = 2000000
            lastThreadName = 'membership-payment-order-persist-1'
            lastCompletedAtEpochMillis = 1787623200000
        }
    }
    $hikariRow = New-MembershipHikariSample `
        -SampledAt '2026-08-24T20:00:00.000Z' `
        -Segment $segment `
        -RuntimeProbe $runtime
    if ([int]$hikariRow.configuredMaximumPoolSize -ne 96 -or
            [int]$hikariRow.callbackMaximumClaimedItems -ne 5000 -or
            [double]$hikariRow.acquireP99Seconds -ne 0.04D) {
        throw 'Hikari or Worker runtime values were flattened incorrectly.'
    }

    $watchSql = New-MembershipPostgresWatchScript
    foreach ($required in @(
            '\echo sampledAt,totalConnections,activeConnections,waitingConnections',
            'FROM pg_stat_activity',
            'WHERE datname = current_database()',
            '\watch 1')) {
        if (-not $watchSql.Contains($required)) {
            throw "PostgreSQL watch script is missing: $required"
        }
    }

    $rawPath = Join-Path $temporaryRoot 'postgres.raw.txt'
    $csvPath = Join-Path $temporaryRoot 'postgres.csv'
    $postgresHeader = 'sampledAt,totalConnections,activeConnections,waitingConnections,navicatTotal,navicatActive,navicatIdleInTransaction,navicatWriteOrDdl'
    @(
        $postgresHeader,
        'Sun Aug 24 20:00:00 2026 (every 1s)',
        '2026-08-24T20:00:00.000Z,40,32,3,4,1,0,0',
        '',
        '2026-08-24T20:00:01.000Z,41,33,2,4,0,0,0') |
        Set-Content -LiteralPath $rawPath -Encoding UTF8
    $converted = Convert-MembershipPostgresWatchOutput `
        -InputPath $rawPath `
        -OutputPath $csvPath
    if ($converted -ne 2) { throw 'PostgreSQL sampler did not retain exactly two rows.' }
    $csvLines = @(Get-Content -LiteralPath $csvPath)
    if ($csvLines.Count -ne 3 -or
            $csvLines[0] -ne $postgresHeader) {
        throw 'PostgreSQL sample CSV is malformed.'
    }
} finally {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
}

Write-Output 'PASS: runtime evidence sampler contracts are stable.'
