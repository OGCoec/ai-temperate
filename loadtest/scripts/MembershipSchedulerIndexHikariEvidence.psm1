Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-MembershipEvidenceSegment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $StatePath
    )

    if (-not (Test-Path -LiteralPath $StatePath -PathType Leaf)) {
        return 'NO_STATE'
    }
    try {
        $state = Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json
        $wave = [string]$state.wave
        if ([string]::IsNullOrWhiteSpace($wave)) {
            return 'UNKNOWN'
        }
        return $wave
    } catch {
        return 'INVALID_STATE'
    }
}

function New-MembershipSchedulerQueueSample {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)] [string] $SampledAt,
        [Parameter(Mandatory = $true)] [string] $Segment,
        [Parameter(Mandatory = $true)] [object] $RedisQueues,
        [Parameter(Mandatory = $true)] [object[]] $RabbitQueues
    )

    $payment = @($RabbitQueues | Where-Object {
        [string]$_.name -eq 'membership.payment.check.queue'
    })
    $closing = @($RabbitQueues | Where-Object {
        [string]$_.name -eq 'membership.closing.check.queue'
    })
    if ($payment.Count -ne 1 -or $closing.Count -ne 1) {
        throw 'RabbitMQ runtime sampling requires exactly one payment queue and one closing queue.'
    }
    return [pscustomobject][ordered]@{
        sampledAt = $SampledAt
        segment = $Segment
        callbackReadySize = [long]$RedisQueues.callbackReadySize
        callbackProcessingSize = [long]$RedisQueues.callbackProcessingSize
        dirtySize = [long]$RedisQueues.dirtySize
        dirtyProcessingSize = [long]$RedisQueues.dirtyProcessingSize
        rabbitPaymentReady = [long]$payment[0].messages_ready
        rabbitPaymentUnacked = [long]$payment[0].messages_unacknowledged
        rabbitClosingReady = [long]$closing[0].messages_ready
        rabbitClosingUnacked = [long]$closing[0].messages_unacknowledged
    }
}

function New-MembershipHikariSample {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)] [string] $SampledAt,
        [Parameter(Mandatory = $true)] [string] $Segment,
        [Parameter(Mandatory = $true)] [object] $RuntimeProbe
    )

    $hikari = $RuntimeProbe.hikari
    $callback = $RuntimeProbe.callbackWorker
    $orderPersist = $RuntimeProbe.orderPersistWorker
    return [pscustomobject][ordered]@{
        sampledAt = $SampledAt
        segment = $Segment
        poolAvailable = [bool]$hikari.poolAvailable
        poolName = [string]$hikari.poolName
        configuredMaximumPoolSize = [int]$hikari.configuredMaximumPoolSize
        configuredMinimumIdle = [int]$hikari.configuredMinimumIdle
        totalConnections = [int]$hikari.totalConnections
        activeConnections = [int]$hikari.activeConnections
        idleConnections = [int]$hikari.idleConnections
        pendingThreads = [int]$hikari.pendingThreads
        timeoutCount = [double]$hikari.timeoutCount
        acquireCount = [long]$hikari.acquire.count
        acquireTotalSeconds = [double]$hikari.acquire.totalSeconds
        acquireMaximumSeconds = [double]$hikari.acquire.maximumSeconds
        acquireP95Seconds = [double]$hikari.acquire.p95Seconds
        acquireP99Seconds = [double]$hikari.acquire.p99Seconds
        usageCount = [long]$hikari.usage.count
        usageTotalSeconds = [double]$hikari.usage.totalSeconds
        usageMaximumSeconds = [double]$hikari.usage.maximumSeconds
        usageP95Seconds = [double]$hikari.usage.p95Seconds
        usageP99Seconds = [double]$hikari.usage.p99Seconds
        callbackRunCount = [long]$callback.runCount
        callbackLastBatches = [int]$callback.lastBatches
        callbackLastClaimedItems = [int]$callback.lastClaimedItems
        callbackMaximumBatches = [int]$callback.maximumBatches
        callbackMaximumClaimedItems = [int]$callback.maximumClaimedItems
        callbackLastOutcome = [string]$callback.lastOutcome
        callbackLastDurationNanos = [long]$callback.lastDurationNanos
        callbackLastThreadName = [string]$callback.lastThreadName
        callbackLastCompletedAtEpochMillis = [long]$callback.lastCompletedAtEpochMillis
        orderPersistRunCount = [long]$orderPersist.runCount
        orderPersistLastBatches = [int]$orderPersist.lastBatches
        orderPersistLastClaimedItems = [int]$orderPersist.lastClaimedItems
        orderPersistMaximumBatches = [int]$orderPersist.maximumBatches
        orderPersistMaximumClaimedItems = [int]$orderPersist.maximumClaimedItems
        orderPersistLastOutcome = [string]$orderPersist.lastOutcome
        orderPersistLastDurationNanos = [long]$orderPersist.lastDurationNanos
        orderPersistLastThreadName = [string]$orderPersist.lastThreadName
        orderPersistLastCompletedAtEpochMillis = [long]$orderPersist.lastCompletedAtEpochMillis
    }
}

function New-MembershipPostgresWatchScript {
    [CmdletBinding()]
    param()

    return @'
\echo sampledAt,totalConnections,activeConnections,waitingConnections,navicatTotal,navicatActive,navicatIdleInTransaction,navicatWriteOrDdl
SELECT
    to_char(
        clock_timestamp() AT TIME ZONE 'UTC',
        'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
    || ',' || COUNT(*)::text
    || ',' || COUNT(*) FILTER (WHERE state = 'active')::text
    || ',' || COUNT(*) FILTER (WHERE wait_event_type IS NOT NULL)::text
    || ',' || COUNT(*) FILTER (WHERE application_name = 'Navicat')::text
    || ',' || COUNT(*) FILTER (
        WHERE application_name = 'Navicat' AND state = 'active')::text
    || ',' || COUNT(*) FILTER (
        WHERE application_name = 'Navicat'
          AND state = 'idle in transaction')::text
    || ',' || COUNT(*) FILTER (
        WHERE application_name = 'Navicat'
          AND query ~* '^[[:space:]]*(insert|update|delete|merge|create|alter|drop|truncate|vacuum|analyze|grant|revoke|lock|copy)[[:space:]]')::text
FROM pg_stat_activity
WHERE datname = current_database();
\watch 1
'@
}

function Convert-MembershipPostgresWatchOutput {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)] [string] $InputPath,
        [Parameter(Mandatory = $true)] [string] $OutputPath
    )

    if (-not (Test-Path -LiteralPath $InputPath -PathType Leaf)) {
        throw "PostgreSQL watch output is missing: $InputPath"
    }
    $parent = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $encoding = [Text.UTF8Encoding]::new($false)
    $writer = [IO.StreamWriter]::new($OutputPath, $false, $encoding)
    $count = 0
    try {
        $writer.WriteLine(
            'sampledAt,totalConnections,activeConnections,waitingConnections,navicatTotal,navicatActive,navicatIdleInTransaction,navicatWriteOrDdl')
        foreach ($line in @(Get-Content -LiteralPath $InputPath)) {
            if ([string]$line -match
                    '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z,\d+,\d+,\d+,\d+,\d+,\d+,\d+$') {
                $writer.WriteLine([string]$line)
                $count += 1
            }
        }
    } finally {
        $writer.Dispose()
    }
    if ($count -eq 0) {
        throw 'PostgreSQL watch output contains no valid connection samples.'
    }
    return $count
}

function Get-MembershipPercentile {
    param(
        [Parameter(Mandatory = $true)] [double[]] $SortedValues,
        [Parameter(Mandatory = $true)] [double] $Percentile
    )

    if ($SortedValues.Count -eq 0) { return $null }
    $index = [Math]::Ceiling($SortedValues.Count * $Percentile) - 1
    $index = [Math]::Max(0, [Math]::Min($SortedValues.Count - 1, $index))
    return [Math]::Round($SortedValues[$index], 3)
}

function New-MembershipCallbackLatencySummary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)] [double[]] $Values
    )

    if ($Values.Count -eq 0) {
        return [pscustomobject][ordered]@{
            count = 0; averageMs = $null; p50Ms = $null; p95Ms = $null
            p99Ms = $null; maximumMs = $null; lessThan1SecondCount = 0
            from1To5SecondsCount = 0; from5To8SecondsCount = 0
            from8To10SecondsCount = 0; atLeast10SecondsCount = 0
        }
    }
    [double[]]$sorted = @($Values | Sort-Object)
    return [pscustomobject][ordered]@{
        count = $sorted.Count
        averageMs = [Math]::Round(($sorted | Measure-Object -Average).Average, 3)
        p50Ms = Get-MembershipPercentile -SortedValues $sorted -Percentile 0.50D
        p95Ms = Get-MembershipPercentile -SortedValues $sorted -Percentile 0.95D
        p99Ms = Get-MembershipPercentile -SortedValues $sorted -Percentile 0.99D
        maximumMs = [Math]::Round($sorted[-1], 3)
        lessThan1SecondCount = @($sorted | Where-Object { $_ -lt 1000D }).Count
        from1To5SecondsCount = @($sorted | Where-Object {
            $_ -ge 1000D -and $_ -lt 5000D
        }).Count
        from5To8SecondsCount = @($sorted | Where-Object {
            $_ -ge 5000D -and $_ -lt 8000D
        }).Count
        from8To10SecondsCount = @($sorted | Where-Object {
            $_ -ge 8000D -and $_ -lt 10000D
        }).Count
        atLeast10SecondsCount = @($sorted | Where-Object { $_ -ge 10000D }).Count
    }
}

function Get-MembershipExplainNodes {
    param([Parameter(Mandatory = $true)] [object] $Node)

    $Node
    if ($Node.PSObject.Properties.Name -contains 'Plans') {
        foreach ($child in @($Node.Plans)) {
            Get-MembershipExplainNodes -Node $child
        }
    }
}

function Test-MembershipLatestPaidExplain {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{
            verdict = '证据不足'; hasExpectedIndex = $false
            hasSort = $false; hasSeqScan = $false; nodeTypes = @()
        }
    }
    try {
        $document = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
        $root = @($document)[0].Plan
        $nodes = @(Get-MembershipExplainNodes -Node $root)
        $nodeTypes = @($nodes | ForEach-Object { [string]$_.'Node Type' })
        $hasIndex = @($nodes | Where-Object {
            $_.PSObject.Properties.Name -contains 'Index Name' -and
            [string]$_.'Index Name' -eq 'idx_membership_order_latest_paid'
        }).Count -gt 0
        $hasSort = $nodeTypes -contains 'Sort'
        $hasSeqScan = $nodeTypes -contains 'Seq Scan'
        return [pscustomobject][ordered]@{
            verdict = if ($hasIndex -and -not $hasSort -and -not $hasSeqScan) {
                'PASS'
            } else {
                'FAIL'
            }
            hasExpectedIndex = $hasIndex
            hasSort = $hasSort
            hasSeqScan = $hasSeqScan
            nodeTypes = $nodeTypes
        }
    } catch {
        return [pscustomobject]@{
            verdict = '证据不足'; hasExpectedIndex = $false
            hasSort = $false; hasSeqScan = $false; nodeTypes = @()
        }
    }
}

function Get-MembershipIndexScanCount {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    $text = Get-Content -Raw -LiteralPath $Path
    $match = [regex]::Match(
        $text,
        'idx_membership_order_latest_paid\s*\|\s*(\d+)\s*\|')
    if ($match.Success) {
        return [long]$match.Groups[1].Value
    }
    return $null
}

function ConvertTo-MembershipEvidenceBoolean([object] $Value) {
    if ($Value -is [bool]) { return [bool]$Value }
    $parsed = $false
    if ([bool]::TryParse([string]$Value, [ref]$parsed)) { return $parsed }
    return $false
}

function Get-MembershipHikariSpecialVerdict {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)] [object[]] $Rows,
        [Parameter(Mandatory = $true)] [double] $DatabaseP99Ms,
        [Parameter(Mandatory = $true)] [int] $ExpectedMaximumPoolSize,
        [Parameter(Mandatory = $true)] [int] $ExpectedMinimumIdle,
        [Parameter(Mandatory = $true)] [int] $PostgresMaxConnections,
        [object[]] $PostgresRows = @(),
        [switch] $SamplerFailure,
        [switch] $ConnectionErrorFound
    )

    if ($Rows.Count -eq 0) {
        return [pscustomobject]@{
            verdict = '不可接受'; reasons = @('Hikari运行样本为空')
            peakConnections = $null; acquireP99Ms = $null
            maximumConsecutivePendingSamples = 0
        }
    }
    $hardReasons = [Collections.Generic.List[string]]::new()
    if ($SamplerFailure) { $hardReasons.Add('运行采样器失败') }
    if ($ConnectionErrorFound) { $hardReasons.Add('发现连接耗尽错误') }
    if (@($Rows | Where-Object {
            -not (ConvertTo-MembershipEvidenceBoolean $_.poolAvailable)
        }).Count -gt 0) {
        $hardReasons.Add('Hikari MXBean不可用')
    }
    if (@($Rows | Where-Object {
            [int]$_.configuredMaximumPoolSize -ne $ExpectedMaximumPoolSize -or
            [int]$_.configuredMinimumIdle -ne $ExpectedMinimumIdle
        }).Count -gt 0) {
        $hardReasons.Add(
            "Hikari配置不是$ExpectedMaximumPoolSize/$ExpectedMinimumIdle")
    }
    $peakConnections = [int](
        $Rows | Measure-Object -Property totalConnections -Maximum).Maximum
    $postgresPeakConnections = if ($PostgresRows.Count -eq 0) {
        $null
    } else {
        [int]($PostgresRows |
            Measure-Object -Property totalConnections -Maximum).Maximum
    }
    if ($peakConnections -ge $PostgresMaxConnections -or
            ($null -ne $postgresPeakConnections -and
                $postgresPeakConnections -ge $PostgresMaxConnections)) {
        $hardReasons.Add('PostgreSQL连接达到硬上限')
    }
    $maximumTimeout = [double](
        $Rows | Measure-Object -Property timeoutCount -Maximum).Maximum
    if ($maximumTimeout -gt 0D) { $hardReasons.Add('Hikari timeout非零') }

    $consecutive = 0
    $maximumConsecutive = 0
    foreach ($row in $Rows) {
        if ([int]$row.pendingThreads -gt 0) {
            $consecutive += 1
            $maximumConsecutive = [Math]::Max($maximumConsecutive, $consecutive)
        } else {
            $consecutive = 0
        }
    }
    $acquireP99Ms = 1000D * [double](
        $Rows | Measure-Object -Property acquireP99Seconds -Maximum).Maximum
    $nonoptimalReasons = [Collections.Generic.List[string]]::new()
    if ($maximumConsecutive -ge 10) {
        $nonoptimalReasons.Add('pending连续至少10个一秒样本为正')
    }
    if ($DatabaseP99Ms -gt 0D -and $acquireP99Ms -ge ($DatabaseP99Ms * 0.5D)) {
        $nonoptimalReasons.Add('连接获取P99达到dbMs P99的50%')
    }
    $verdict = if ($hardReasons.Count -gt 0) {
        '不可接受'
    } elseif ($nonoptimalReasons.Count -gt 0) {
        '可运行但非最优'
    } else {
        '可接受'
    }
    return [pscustomobject][ordered]@{
        verdict = $verdict
        reasons = @($hardReasons) + @($nonoptimalReasons)
        peakConnections = $peakConnections
        postgresPeakConnections = $postgresPeakConnections
        acquireP99Ms = [Math]::Round($acquireP99Ms, 3)
        databaseP99Ms = $DatabaseP99Ms
        maximumConsecutivePendingSamples = $maximumConsecutive
        maximumTimeoutCount = $maximumTimeout
        expectedMaximumPoolSize = $ExpectedMaximumPoolSize
        expectedMinimumIdle = $ExpectedMinimumIdle
        postgresMaxConnections = $PostgresMaxConnections
    }
}

function Test-MembershipOldBoundaryPlateau {
    param(
        [Parameter(Mandatory = $true)] [object[]] $Rows,
        [Parameter(Mandatory = $true)] [string] $ReadyProperty,
        [Parameter(Mandatory = $true)] [string] $ProcessingProperty
    )

    foreach ($segment in @($Rows | Group-Object segment)) {
        $ordered = @($segment.Group | Sort-Object {
            [datetimeoffset]$_.sampledAt
        })
        for ($index = 1; $index -lt $ordered.Count; $index++) {
            $previous = [long]$ordered[$index - 1].$ReadyProperty +
                [long]$ordered[$index - 1].$ProcessingProperty
            $current = [long]$ordered[$index].$ReadyProperty +
                [long]$ordered[$index].$ProcessingProperty
            $drop = $previous - $current
            if ($drop -lt 1500L -or $drop -gt 2500L -or $current -le 0L) {
                continue
            }
            $startedAt = [datetimeoffset]$ordered[$index].sampledAt
            for ($probe = $index + 1; $probe -lt $ordered.Count; $probe++) {
                $value = [long]$ordered[$probe].$ReadyProperty +
                    [long]$ordered[$probe].$ProcessingProperty
                if ([Math]::Abs($value - $current) -gt 50L) { break }
                $elapsedMilliseconds = (
                    [datetimeoffset]$ordered[$probe].sampledAt - $startedAt
                ).TotalMilliseconds
                if ($elapsedMilliseconds -ge 4500D) {
                    return $true
                }
            }
        }
    }
    return $false
}

function Get-MembershipSchedulerSpecialVerdict {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)] [string] $ThreadDumpText,
        [Parameter(Mandatory = $true)] [object[]] $QueueRows,
        [Parameter(Mandatory = $true)] [object[]] $RuntimeRows,
        [Parameter(Mandatory = $true)] [object] $LatencySummary
    )

    if ($QueueRows.Count -eq 0 -or $RuntimeRows.Count -eq 0 -or
            [int]$LatencySummary.count -eq 0) {
        return [pscustomobject]@{
            verdict = '证据不足'; reasons = @('调度专项样本不完整')
            callbackCrossedOldBoundary = $false
            orderPersistCrossedOldBoundary = $false
        }
    }
    $reasons = [Collections.Generic.List[string]]::new()
    $callbackThread = $ThreadDumpText.Contains('membership-payment-callback-')
    $orderThread = $ThreadDumpText.Contains('membership-payment-order-persist-')
    if (-not $callbackThread) { $reasons.Add('缺少Callback专用线程') }
    if (-not $orderThread) { $reasons.Add('缺少OrderPersist专用线程') }

    $callbackMaximumItems = [int](
        $RuntimeRows | Measure-Object -Property callbackMaximumClaimedItems -Maximum).Maximum
    $orderMaximumItems = [int](
        $RuntimeRows | Measure-Object -Property orderPersistMaximumClaimedItems -Maximum).Maximum
    $callbackMaximumBatches = [int](
        $RuntimeRows | Measure-Object -Property callbackMaximumBatches -Maximum).Maximum
    $orderMaximumBatches = [int](
        $RuntimeRows | Measure-Object -Property orderPersistMaximumBatches -Maximum).Maximum
    $callbackPeakBacklog = [long](
        $QueueRows | ForEach-Object {
            [long]$_.callbackReadySize + [long]$_.callbackProcessingSize
        } | Measure-Object -Maximum).Maximum
    $orderPeakBacklog = [long](
        $QueueRows | ForEach-Object {
            [long]$_.dirtySize + [long]$_.dirtyProcessingSize
        } | Measure-Object -Maximum).Maximum
    $callbackCrossed = $callbackMaximumItems -gt 2000 -or
        $callbackPeakBacklog -ge 2001
    $orderCrossed = $orderMaximumItems -gt 2000 -or $orderPeakBacklog -ge 2001
    $callbackPlateau = Test-MembershipOldBoundaryPlateau `
        -Rows $QueueRows `
        -ReadyProperty 'callbackReadySize' `
        -ProcessingProperty 'callbackProcessingSize'
    $orderPlateau = Test-MembershipOldBoundaryPlateau `
        -Rows $QueueRows `
        -ReadyProperty 'dirtySize' `
        -ProcessingProperty 'dirtyProcessingSize'
    if ($callbackPlateau) { $reasons.Add('Callback出现旧2000条固定阶梯') }
    if ($orderPlateau) { $reasons.Add('OrderPersist出现旧2000条固定阶梯') }
    if ($callbackMaximumBatches -gt 50 -or $orderMaximumBatches -gt 50) {
        $reasons.Add('Worker单轮批次数超过50的有界合同')
    }
    $final = $QueueRows[-1]
    if (([long]$final.callbackReadySize + [long]$final.callbackProcessingSize +
            [long]$final.dirtySize + [long]$final.dirtyProcessingSize) -ne 0L) {
        $reasons.Add('最终四个Redis工作集合未归零')
    }
    if ([int]$LatencySummary.atLeast10SecondsCount -gt 0) {
        $reasons.Add('存在至少10秒的回调解析延迟')
    }
    if ([double]$LatencySummary.maximumMs -ge 8000D) {
        $reasons.Add('回调解析最大延迟未低于8秒')
    }
    $hardFailure = $reasons.Count -gt 0
    $verdict = if ($hardFailure) {
        'FAIL'
    } elseif (-not $callbackCrossed -or -not $orderCrossed) {
        '证据不足'
    } else {
        'PASS'
    }
    return [pscustomobject][ordered]@{
        verdict = $verdict
        reasons = @($reasons)
        callbackDedicatedThread = $callbackThread
        orderPersistDedicatedThread = $orderThread
        callbackCrossedOldBoundary = $callbackCrossed
        orderPersistCrossedOldBoundary = $orderCrossed
        callbackMaximumBatches = $callbackMaximumBatches
        orderPersistMaximumBatches = $orderMaximumBatches
        callbackMaximumClaimedItems = $callbackMaximumItems
        orderPersistMaximumClaimedItems = $orderMaximumItems
        callbackPeakBacklog = $callbackPeakBacklog
        orderPersistPeakBacklog = $orderPeakBacklog
        callbackOldBoundaryPlateauDetected = $callbackPlateau
        orderPersistOldBoundaryPlateauDetected = $orderPlateau
    }
}

Export-ModuleMember -Function @(
    'Get-MembershipEvidenceSegment',
    'New-MembershipSchedulerQueueSample',
    'New-MembershipHikariSample',
    'New-MembershipPostgresWatchScript',
    'Convert-MembershipPostgresWatchOutput',
    'New-MembershipCallbackLatencySummary',
    'Test-MembershipLatestPaidExplain',
    'Get-MembershipIndexScanCount',
    'ConvertTo-MembershipEvidenceBoolean',
    'Get-MembershipHikariSpecialVerdict',
    'Get-MembershipSchedulerSpecialVerdict')
