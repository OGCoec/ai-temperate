[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,128}$')]
    [string] $RunId,
    [ValidatePattern('^$|^[A-Za-z0-9_-]{1,128}$')]
    [string] $HttpLogRunId = '',
    [Parameter(Mandatory = $true)]
    [ValidateSet('E-P1','E-PR','E-A1','E-AR','H-P1','H-PR','H-A1','H-AR')]
    [string] $GroupCode,
    [Parameter(Mandatory = $true)]
    [string] $ScenarioOrdersCsvPath,
    [Parameter(Mandatory = $true)]
    [string] $HttpEventsLogPath,
    [Parameter(Mandatory = $true)]
    [string] $FocusedEventsCsvPath,
    [Parameter(Mandatory = $true)]
    [string] $RequestResultsCsvPath,
    [Parameter(Mandatory = $true)]
    [string] $QueueSamplesCsvPath,
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,
    [ValidateRange(1D, 1000000D)]
    [double] $MinimumWindowQps = 900D,
    [ValidateRange(0D, 1D)]
    [double] $MaximumWindowQpsDifferenceRatio = 0.10D,
    [ValidateRange(1D, 10D)]
    [double] $MaximumLatencyGrowthRatio = 1.10D,
    [switch] $AllowEventsOutsideScenarioManifest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$invariant = [Globalization.CultureInfo]::InvariantCulture
$eventMarker = 'event=membership_order_create_http_completed '
if ([string]::IsNullOrWhiteSpace($HttpLogRunId)) { $HttpLogRunId = $RunId }
$golden = [ordered]@{
    runId = 'membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048'
    wallClockMicros = 4909846L
    qps = 1018.362D
    orderCreateP50Ms = 150.485D
    orderCreateP95Ms = 293.444D
    orderCreateP99Ms = 378.326D
    redisQueueP50Ms = 40.221D
    redisQueueP95Ms = 142.899D
    redisQueueP99Ms = 202.945D
    redisPipelineExecuteP50Ms = 63.921D
    redisPipelineExecuteP95Ms = 120.872D
    redisPipelineExecuteP99Ms = 159.847D
    rabbitPublishP50Ms = 27.684D
    rabbitPublishP95Ms = 79.089D
    rabbitPublishP99Ms = 153.855D
    rabbitConfirmP50Ms = 18.655D
    rabbitConfirmP95Ms = 54.832D
    rabbitConfirmP99Ms = 81.012D
    dbTransactionP50Ms = 1.284D
    dbTransactionP95Ms = 3.622D
    dbTransactionP99Ms = 11.407D
    frontHalfQps = 832.511D
    backHalfQps = 1188.341D
}

function Get-Field([psobject] $Row, [string[]] $Names) {
    foreach ($name in $Names) {
        $property = $Row.PSObject.Properties[$name]
        if ($null -ne $property -and
                -not [string]::IsNullOrWhiteSpace([string]$property.Value)) {
            return [string]$property.Value
        }
    }
    return $null
}

function Get-NearestRank([double[]] $Sorted, [double] $Percentile) {
    if ($Sorted.Count -eq 0) { return $null }
    [Array]::Sort($Sorted)
    $index = [Math]::Ceiling($Sorted.Count * $Percentile) - 1
    return [Math]::Round(
        $Sorted[[Math]::Max(0, [Math]::Min($Sorted.Count - 1, $index))], 3)
}

function Convert-EvidenceTimeToEpochMicros([string] $Value) {
    $parsed = [datetimeoffset]::ParseExact(
        $Value,
        "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'",
        $invariant,
        [Globalization.DateTimeStyles]::AssumeUniversal -bor
            [Globalization.DateTimeStyles]::AdjustToUniversal)
    return $parsed.ToUnixTimeMilliseconds() * 1000L +
        [long](($parsed.Ticks % [TimeSpan]::TicksPerMillisecond) / 10L)
}

function Test-ZeroQueueSample([psobject] $Row) {
    foreach ($name in @(
            'callbackReadySize','callbackProcessingSize','dirtySize','dirtyProcessingSize',
            'rabbitPaymentReady','rabbitPaymentUnacked',
            'rabbitClosingReady','rabbitClosingUnacked')) {
        $property = $Row.PSObject.Properties[$name]
        if ($null -eq $property -or [long]$property.Value -ne 0L) {
            return $false
        }
    }
    return $true
}

foreach ($path in @(
        $ScenarioOrdersCsvPath, $HttpEventsLogPath, $FocusedEventsCsvPath,
        $RequestResultsCsvPath, $QueueSamplesCsvPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Warmup stability evidence file does not exist: $path"
    }
}

$scenarioByTrace = [Collections.Generic.Dictionary[string, string]]::new(
    [StringComparer]::Ordinal)
$traceByOrder = [Collections.Generic.Dictionary[string, string]]::new(
    [StringComparer]::Ordinal)
$scenarioUsers = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($row in @(Import-Csv -LiteralPath $ScenarioOrdersCsvPath)) {
    $rowRun = Get-Field $row @('run_id','runId')
    $rowGroup = Get-Field $row @('group_code','groupCode')
    $traceId = Get-Field $row @('trace_id','traceId')
    $orderId = Get-Field $row @('order_id','orderIdB64')
    $userId = Get-Field $row @('user_id','userId')
    if ($rowRun -ne $RunId -or $rowGroup -ne $GroupCode -or
            [string]::IsNullOrWhiteSpace($traceId) -or
            $userId -notmatch '^700000000000[0-7][0-9]{4}$' -or
            $orderId -notmatch '^[A-Za-z0-9_-]{22}$' -or
            $scenarioByTrace.ContainsKey($traceId) -or
            $traceByOrder.ContainsKey($orderId) -or
            -not $scenarioUsers.Add($userId)) {
        throw 'Warmup scenario manifest contains an invalid or duplicate row.'
    }
    $scenarioByTrace.Add($traceId, $orderId)
    $traceByOrder.Add($orderId, $traceId)
}
$expectedCount = $scenarioByTrace.Count
if ($expectedCount -notin @(5000, 10000) -or $scenarioUsers.Count -ne $expectedCount) {
    throw 'Warmup stability report accepts exactly 5,000 or 10,000 real orders.'
}

$httpByTrace = [Collections.Generic.Dictionary[string, object]]::new(
    [StringComparer]::Ordinal)
$outsideScenarioHttpEventCount = 0L
$logShare = [IO.FileShare]([int][IO.FileShare]::ReadWrite -bor [int][IO.FileShare]::Delete)
$stream = [IO.FileStream]::new(
    $HttpEventsLogPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, $logShare)
$reader = [IO.StreamReader]::new($stream, [Text.UTF8Encoding]::new($false), $true)
try {
    while ($null -ne ($line = $reader.ReadLine())) {
        $markerIndex = $line.IndexOf($eventMarker, [StringComparison]::Ordinal)
        if ($markerIndex -lt 0) { continue }
        $fields = @{}
        foreach ($match in [regex]::Matches(
                $line.Substring($markerIndex),
                '(?<key>[A-Za-z][A-Za-z0-9]*)=(?<value>[^\s]+)')) {
            $fields[$match.Groups['key'].Value] = $match.Groups['value'].Value
        }
        $eventRunId = if ($fields.ContainsKey('r')) { [string]$fields.r } else { '' }
        if ($eventRunId -ne $HttpLogRunId -or
                -not $fields.ContainsKey('sg') -or [string]$fields.sg -ne $GroupCode) {
            continue
        }
        foreach ($required in @('tr','recv','done','dur','status','committed')) {
            if (-not $fields.ContainsKey($required)) {
                throw "Warmup HTTP event is missing field: $required"
            }
        }
        $traceId = [string]$fields.tr
        if (-not $scenarioByTrace.ContainsKey($traceId)) {
            if ($AllowEventsOutsideScenarioManifest) {
                $outsideScenarioHttpEventCount += 1L
                continue
            }
            throw 'Warmup HTTP evidence is outside the manifest.'
        }
        if ($httpByTrace.ContainsKey($traceId)) {
            throw 'Warmup HTTP evidence contains a duplicate manifest trace.'
        }
        $received = [long]$fields.recv
        $completed = [long]$fields.done
        $duration = [long]$fields.dur
        $status = [int]$fields.status
        $committed = [string]$fields.committed -eq 'true'
        if ($received -lt 0L -or $completed -lt $received -or
                $duration -ne $completed - $received) {
            throw 'Warmup HTTP evidence contains an invalid interval.'
        }
        $httpByTrace.Add($traceId, [pscustomobject]@{
            traceId=$traceId
            orderId=$scenarioByTrace[$traceId]
            receivedAtEpochMicros=$received
            completedAtEpochMicros=$completed
            durationMicros=$duration
            status=$status
            committed=$committed
        })
    }
} finally {
    $reader.Dispose()
}

$focusedByOrder = [Collections.Generic.Dictionary[string, object]]::new(
    [StringComparer]::Ordinal)
$focusedRelevant = @(
    Import-Csv -LiteralPath $FocusedEventsCsvPath | Where-Object {
        (Get-Field $_ @('runId','run_id')) -eq $RunId -and
        (Get-Field $_ @('groupCode','group_code')) -eq $GroupCode -and
        (Get-Field $_ @('operation')) -eq 'ORDER_CREATE'
    })
foreach ($row in $focusedRelevant) {
    $orderId = Get-Field $row @('orderIdB64','order_id')
    if (-not $traceByOrder.ContainsKey($orderId) -or
            $focusedByOrder.ContainsKey($orderId)) {
        throw 'Warmup focused timing contains an outside or duplicate order.'
    }
    $focusedByOrder.Add($orderId, [pscustomobject]@{
        outcome = Get-Field $row @('outcome')
        totalMs = [double]::Parse((Get-Field $row @('totalMs')), $invariant)
        redisWriteQueueWaitMs = [double]::Parse(
            (Get-Field $row @('redisWriteQueueWaitMs')), $invariant)
        redisPipelineExecuteMs = [double]::Parse(
            (Get-Field $row @('redisPipelineExecuteMs')), $invariant)
        rabbitPublishConfirmMs = [double]::Parse(
            (Get-Field $row @('rabbitPublishConfirmMs')), $invariant)
        rabbitConfirmWaitMs = [double]::Parse(
            (Get-Field $row @('rabbitConfirmWaitMs')), $invariant)
        dbTransactionMs = [double]::Parse(
            (Get-Field $row @('dbTransactionMs')), $invariant)
    })
}

$successfulHttp = @($httpByTrace.Values | Where-Object {
    $_.status -eq 201 -and $_.committed
} | Sort-Object receivedAtEpochMicros, traceId)
$focusedSuccess = @($focusedByOrder.Values | Where-Object outcome -eq 'SUCCESS')
$paymentRows = @(Import-Csv -LiteralPath $RequestResultsCsvPath | Where-Object {
    (Get-Field $_ @('run_id','runId')) -eq $RunId -and
    (Get-Field $_ @('group_code','groupCode')) -eq $GroupCode -and
    (Get-Field $_ @('operation')) -in @('START_PAYMENT','PAYMENT_ATTEMPT') -and
    [string]::Equals(
        (Get-Field $_ @('success')), 'true', [StringComparison]::OrdinalIgnoreCase) -and
    [int](Get-Field $_ @('http_status','httpStatus')) -in @(200, 201)
})
$firstPaymentStarted = if ($paymentRows.Count -eq 0) { $null } else {
    [long](($paymentRows | ForEach-Object {
        Convert-EvidenceTimeToEpochMicros (Get-Field $_ @('started_at','startedAt'))
    } | Measure-Object -Minimum).Minimum)
}
$lastCreateCompleted = if ($successfulHttp.Count -eq 0) { $null } else {
    [long](($successfulHttp | Measure-Object completedAtEpochMicros -Maximum).Maximum)
}
$paymentOverlap = $paymentRows.Count -eq $expectedCount -and
    $null -ne $firstPaymentStarted -and $null -ne $lastCreateCompleted -and
    $firstPaymentStarted -lt $lastCreateCompleted

$windows = [Collections.Generic.List[object]]::new()
for ($offset = 0; $offset -lt $successfulHttp.Count; $offset += 500) {
    $events = @($successfulHttp[$offset..([Math]::Min($offset + 499, $successfulHttp.Count - 1))])
    [double[]]$redisValues = @($events | ForEach-Object {
        if (-not $focusedByOrder.ContainsKey($_.orderId)) {
            throw "Warmup timing is missing order: $($_.orderId)"
        }
        [double]$focusedByOrder[$_.orderId].redisPipelineExecuteMs
    })
    [double[]]$rabbitValues = @($events | ForEach-Object {
        [double]$focusedByOrder[$_.orderId].rabbitConfirmWaitMs
    })
    $firstReceived = [long](($events | Measure-Object receivedAtEpochMicros -Minimum).Minimum)
    $lastCompleted = [long](($events | Measure-Object completedAtEpochMicros -Maximum).Maximum)
    $wallMicros = $lastCompleted - $firstReceived
    $qps = if ($wallMicros -le 0L) { 0D } else {
        [Math]::Round($events.Count * 1000000D / $wallMicros, 3)
    }
    $windows.Add([pscustomobject][ordered]@{
        windowIndex = [int]($offset / 500) + 1
        count = $events.Count
        firstReceivedAtEpochMicros = $firstReceived
        lastCompletedAtEpochMicros = $lastCompleted
        wallClockMicros = $wallMicros
        qps = $qps
        redisPipelineExecuteP95Ms = Get-NearestRank $redisValues 0.95
        rabbitConfirmWaitP95Ms = Get-NearestRank $rabbitValues 0.95
    })
}

$queueRows = @(Import-Csv -LiteralPath $QueueSamplesCsvPath)
$lastThreeQueues = @(
    if ($queueRows.Count -ge 3) {
        $queueRows[($queueRows.Count - 3)..($queueRows.Count - 1)]
    }
)
$queuesDrained = $lastThreeQueues.Count -eq 3 -and
    @($lastThreeQueues | Where-Object { -not (Test-ZeroQueueSample $_) }).Count -eq 0
$lastTwo = @(
    if ($windows.Count -ge 2) {
        $windows[($windows.Count - 2)..($windows.Count - 1)]
    }
)
$qpsDifferenceRatio = if ($lastTwo.Count -ne 2 -or
        [Math]::Max([double]$lastTwo[0].qps, [double]$lastTwo[1].qps) -le 0D) {
    [double]::PositiveInfinity
} else {
    [Math]::Abs([double]$lastTwo[1].qps - [double]$lastTwo[0].qps) /
        [Math]::Max([double]$lastTwo[0].qps, [double]$lastTwo[1].qps)
}
$stableTailDiagnostic = $lastTwo.Count -eq 2 -and
    @($lastTwo | Where-Object {
        $_.count -ne 500 -or [double]$_.qps -lt $MinimumWindowQps
    }).Count -eq 0 -and
    $qpsDifferenceRatio -le $MaximumWindowQpsDifferenceRatio -and
    [double]$lastTwo[1].redisPipelineExecuteP95Ms -le
        [double]$lastTwo[0].redisPipelineExecuteP95Ms * $MaximumLatencyGrowthRatio -and
    [double]$lastTwo[1].rabbitConfirmWaitP95Ms -le
        [double]$lastTwo[0].rabbitConfirmWaitP95Ms * $MaximumLatencyGrowthRatio
$completeEvidence = $httpByTrace.Count -eq $expectedCount -and
    $successfulHttp.Count -eq $expectedCount -and
    $focusedRelevant.Count -eq $expectedCount -and
    $focusedSuccess.Count -eq $expectedCount -and
    $windows.Count -eq ($expectedCount / 500) -and
    $paymentOverlap -and $queuesDrained
$failureCode = if ($httpByTrace.Count -ne $expectedCount -or
        $successfulHttp.Count -ne $expectedCount -or
        $windows.Count -ne ($expectedCount / 500)) {
    'WARMUP_HTTP_EVIDENCE_INCOMPLETE'
} elseif ($focusedRelevant.Count -ne $expectedCount -or
        $focusedSuccess.Count -ne $expectedCount) {
    'WARMUP_FOCUSED_EVIDENCE_INCOMPLETE'
} elseif (-not $paymentOverlap) {
    'WARMUP_PAYMENT_OVERLAP_MISSING'
} elseif (-not $queuesDrained) {
    'WARMUP_QUEUE_DRAIN_INCOMPLETE'
} else { $null }

$fullFirstReceived = if ($successfulHttp.Count -eq 0) { $null } else {
    [long](($successfulHttp | Measure-Object receivedAtEpochMicros -Minimum).Minimum)
}
$fullLastCompleted = if ($successfulHttp.Count -eq 0) { $null } else {
    [long](($successfulHttp | Measure-Object completedAtEpochMicros -Maximum).Maximum)
}
$fullWallMicros = if ($null -eq $fullFirstReceived -or $null -eq $fullLastCompleted) {
    0L
} else {
    $fullLastCompleted - $fullFirstReceived
}
# 放行必须使用未四舍五入的完整区段 QPS；格式化只发生在证据输出阶段。
$fullQpsRaw = if ($fullWallMicros -le 0L) { 0D } else {
    $successfulHttp.Count * 1000000D / $fullWallMicros
}
$successfulDurationMicros = if ($successfulHttp.Count -eq 0) { 0L } else {
    [long](($successfulHttp | Measure-Object durationMicros -Sum).Sum)
}
$effectiveCreateConcurrency = if ($fullWallMicros -le 0L) { 0D } else {
    $successfulDurationMicros / [double]$fullWallMicros
}
$maximumWallClockSeconds = if ($expectedCount -eq 5000) { 5.556D } else { 11.112D }
$goldenWallClockSeconds = if ($expectedCount -eq 5000) { 5D } else { 10D }
$contractPerformancePassed = $fullWallMicros -le
        [long]($maximumWallClockSeconds * 1000000D) -and
    $fullQpsRaw -ge 900D
$goldenCapabilityReached = $fullWallMicros -lt
        [long]($goldenWallClockSeconds * 1000000D) -and
    $fullQpsRaw -gt 1000D
$performanceClassification = if ($goldenCapabilityReached) {
    if ($expectedCount -eq 5000) { 'GOLDEN_REPRODUCED' } else { 'GOLDEN_CAPABILITY_TARGET_REACHED' }
} elseif ($contractPerformancePassed) {
    'CONTRACT_PASS_WITH_REGRESSION'
} else {
    'NOT_QUALIFIED'
}
$verdict = if ($completeEvidence -and $contractPerformancePassed) { 'PASS' } else { 'FAIL' }

function Get-SliceMetrics([object[]] $Events) {
    if ($Events.Count -eq 0) {
        return [pscustomobject]@{ count=0; wallClockMicros=0L; wallClockSeconds=0D; qps=0D }
    }
    $first = [long](($Events | Measure-Object receivedAtEpochMicros -Minimum).Minimum)
    $last = [long](($Events | Measure-Object completedAtEpochMicros -Maximum).Maximum)
    $wall = $last - $first
    $rawQps = if ($wall -le 0L) { 0D } else { $Events.Count * 1000000D / $wall }
    return [pscustomobject][ordered]@{
        count = $Events.Count
        firstReceivedAtEpochMicros = $first
        lastCompletedAtEpochMicros = $last
        wallClockMicros = $wall
        wallClockSeconds = [Math]::Round($wall / 1000000D, 6)
        qps = [Math]::Round($rawQps, 3)
    }
}

$halfCount = [int]($expectedCount / 2)
$frontHalf = if ($successfulHttp.Count -ge $halfCount) {
    Get-SliceMetrics @($successfulHttp[0..($halfCount - 1)])
} else { Get-SliceMetrics @() }
$backHalf = if ($successfulHttp.Count -eq $expectedCount) {
    Get-SliceMetrics @($successfulHttp[$halfCount..($expectedCount - 1)])
} else { Get-SliceMetrics @() }
$halfQpsAbsoluteDifference = [double]$backHalf.qps - [double]$frontHalf.qps
$halfQpsRelativeDifferencePercent = if ([double]$frontHalf.qps -eq 0D) { $null } else {
    ($halfQpsAbsoluteDifference / [double]$frontHalf.qps) * 100D
}

[double[]]$totalValues = @($focusedSuccess | ForEach-Object { [double]$_.totalMs })
[double[]]$redisQueueValues = @($focusedSuccess | ForEach-Object { [double]$_.redisWriteQueueWaitMs })
[double[]]$pipelineValues = @($focusedSuccess | ForEach-Object { [double]$_.redisPipelineExecuteMs })
[double[]]$rabbitPublishValues = @($focusedSuccess | ForEach-Object { [double]$_.rabbitPublishConfirmMs })
[double[]]$rabbitConfirmValues = @($focusedSuccess | ForEach-Object { [double]$_.rabbitConfirmWaitMs })
[double[]]$dbTransactionValues = @($focusedSuccess | ForEach-Object { [double]$_.dbTransactionMs })
$currentLayers = [ordered]@{
    orderCreate = [ordered]@{ p50Ms=Get-NearestRank $totalValues 0.50; p95Ms=Get-NearestRank $totalValues 0.95; p99Ms=Get-NearestRank $totalValues 0.99 }
    redisQueue = [ordered]@{ p50Ms=Get-NearestRank $redisQueueValues 0.50; p95Ms=Get-NearestRank $redisQueueValues 0.95; p99Ms=Get-NearestRank $redisQueueValues 0.99 }
    redisPipelineExecute = [ordered]@{ p50Ms=Get-NearestRank $pipelineValues 0.50; p95Ms=Get-NearestRank $pipelineValues 0.95; p99Ms=Get-NearestRank $pipelineValues 0.99 }
    rabbitPublish = [ordered]@{ p50Ms=Get-NearestRank $rabbitPublishValues 0.50; p95Ms=Get-NearestRank $rabbitPublishValues 0.95; p99Ms=Get-NearestRank $rabbitPublishValues 0.99 }
    rabbitConfirm = [ordered]@{ p50Ms=Get-NearestRank $rabbitConfirmValues 0.50; p95Ms=Get-NearestRank $rabbitConfirmValues 0.95; p99Ms=Get-NearestRank $rabbitConfirmValues 0.99 }
    dbTransaction = [ordered]@{ p50Ms=Get-NearestRank $dbTransactionValues 0.50; p95Ms=Get-NearestRank $dbTransactionValues 0.95; p99Ms=Get-NearestRank $dbTransactionValues 0.99 }
}

function New-Delta([double] $Current, [double] $Baseline) {
    return [ordered]@{
        absolute = [Math]::Round($Current - $Baseline, 6)
        percent = [Math]::Round(($Current / $Baseline - 1D) * 100D, 6)
    }
}

$goldenDeltas = [ordered]@{
    wallClockSeconds = New-Delta ($fullWallMicros / 1000000D) ($golden.wallClockMicros / 1000000D)
    qps = New-Delta $fullQpsRaw $golden.qps
    orderCreateP50Ms = New-Delta $currentLayers.orderCreate.p50Ms $golden.orderCreateP50Ms
    orderCreateP95Ms = New-Delta $currentLayers.orderCreate.p95Ms $golden.orderCreateP95Ms
    orderCreateP99Ms = New-Delta $currentLayers.orderCreate.p99Ms $golden.orderCreateP99Ms
    redisQueueP50Ms = New-Delta $currentLayers.redisQueue.p50Ms $golden.redisQueueP50Ms
    redisQueueP95Ms = New-Delta $currentLayers.redisQueue.p95Ms $golden.redisQueueP95Ms
    redisQueueP99Ms = New-Delta $currentLayers.redisQueue.p99Ms $golden.redisQueueP99Ms
    redisPipelineExecuteP50Ms = New-Delta $currentLayers.redisPipelineExecute.p50Ms $golden.redisPipelineExecuteP50Ms
    redisPipelineExecuteP95Ms = New-Delta $currentLayers.redisPipelineExecute.p95Ms $golden.redisPipelineExecuteP95Ms
    redisPipelineExecuteP99Ms = New-Delta $currentLayers.redisPipelineExecute.p99Ms $golden.redisPipelineExecuteP99Ms
    rabbitPublishP50Ms = New-Delta $currentLayers.rabbitPublish.p50Ms $golden.rabbitPublishP50Ms
    rabbitPublishP95Ms = New-Delta $currentLayers.rabbitPublish.p95Ms $golden.rabbitPublishP95Ms
    rabbitPublishP99Ms = New-Delta $currentLayers.rabbitPublish.p99Ms $golden.rabbitPublishP99Ms
    rabbitConfirmP50Ms = New-Delta $currentLayers.rabbitConfirm.p50Ms $golden.rabbitConfirmP50Ms
    rabbitConfirmP95Ms = New-Delta $currentLayers.rabbitConfirm.p95Ms $golden.rabbitConfirmP95Ms
    rabbitConfirmP99Ms = New-Delta $currentLayers.rabbitConfirm.p99Ms $golden.rabbitConfirmP99Ms
    dbTransactionP50Ms = New-Delta $currentLayers.dbTransaction.p50Ms $golden.dbTransactionP50Ms
    dbTransactionP95Ms = New-Delta $currentLayers.dbTransaction.p95Ms $golden.dbTransactionP95Ms
    dbTransactionP99Ms = New-Delta $currentLayers.dbTransaction.p99Ms $golden.dbTransactionP99Ms
    frontHalfQps = New-Delta $frontHalf.qps $golden.frontHalfQps
    backHalfQps = New-Delta $backHalf.qps $golden.backHalfQps
}

$fullOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $fullOutput -Force | Out-Null
$windowsPath = Join-Path $fullOutput 'stability-windows.csv'
$windows | Export-Csv -LiteralPath $windowsPath -NoTypeInformation -Encoding utf8
$verdictPath = Join-Path $fullOutput 'verdict.json'
([ordered]@{
    verdict = $verdict
    runId = $RunId
    httpLogRunId = $HttpLogRunId
    groupCode = $GroupCode
    expectedCount = $expectedCount
    http201Count = $successfulHttp.Count
    outsideScenarioHttpEventCount = $outsideScenarioHttpEventCount
    outsideScenarioEventsAllowed = [bool]$AllowEventsOutsideScenarioManifest
    focusedSuccessCount = $focusedSuccess.Count
    successfulPaymentCount = $paymentRows.Count
    paymentOverlap = $paymentOverlap
    queuesDrainedForThreeSamples = $queuesDrained
    functionalPassed = $completeEvidence
    failureCode = $failureCode
    contractPerformancePassed = $contractPerformancePassed
    performanceClassification = $performanceClassification
    goldenCapabilityReached = $goldenCapabilityReached
    fullFirstReceivedAtEpochMicros = $fullFirstReceived
    fullLastCompletedAtEpochMicros = $fullLastCompleted
    fullWallClockMicros = $fullWallMicros
    fullWallClockSeconds = [Math]::Round($fullWallMicros / 1000000D, 6)
    fullHttpQps = [Math]::Round($fullQpsRaw, 6)
    effectiveCreateConcurrency = [Math]::Round($effectiveCreateConcurrency, 6)
    httpStatusCounts = [ordered]@{
        http201Committed = $successfulHttp.Count
        non201OrUncommitted = $httpByTrace.Count - $successfulHttp.Count
        duplicateTraceCount = 0
        outsideManifestEventCount = 0
    }
    maximumWallClockSeconds = $maximumWallClockSeconds
    minimumFullQps = 900D
    frontHalf = $frontHalf
    backHalf = $backHalf
    backHalfMinusFrontHalfQps = [Math]::Round($halfQpsAbsoluteDifference, 6)
    backHalfRelativeToFrontHalfPercent = if ($null -eq $halfQpsRelativeDifferencePercent) {
        $null
    } else { [Math]::Round($halfQpsRelativeDifferencePercent, 6) }
    focusedLatency = $currentLayers
    goldenBaseline = $golden
    goldenDeltas = $goldenDeltas
    qpsDifferenceRatio = if ([double]::IsInfinity($qpsDifferenceRatio)) {
        $null
    } else { [Math]::Round($qpsDifferenceRatio, 6) }
    minimumWindowQps = $MinimumWindowQps
    maximumWindowQpsDifferenceRatio = $MaximumWindowQpsDifferenceRatio
    maximumLatencyGrowthRatio = $MaximumLatencyGrowthRatio
    lastTwoWindowsDiagnosticOnly = $true
    lastTwoWindowsDiagnosticPassed = $stableTailDiagnostic
    lastTwoWindows = $lastTwo
    artifacts = [ordered]@{ stabilityWindowsCsv = $windowsPath }
}) | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $verdictPath -Encoding utf8

Write-Output "Membership warmup stability report written to: $fullOutput"
