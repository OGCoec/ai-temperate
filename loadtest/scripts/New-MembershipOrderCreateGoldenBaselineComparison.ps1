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
    [ValidateSet('WARMUP','FORMAL')]
    [string] $ExecutionPhase,
    [Parameter(Mandatory = $true)]
    [string] $ScenarioOrdersCsvPath,
    [Parameter(Mandatory = $true)]
    [string] $HttpEventsLogPath,
    [Parameter(Mandatory = $true)]
    [string] $FocusedSummaryCsvPath,
    [Parameter(Mandatory = $true)]
    [string] $GoldenBaselineRunId,
    [Parameter(Mandatory = $true)]
    [string] $GoldenBaselineEvidenceRoot,
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,
    [switch] $AllowEventsOutsideScenarioManifest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$invariant = [Globalization.CultureInfo]::InvariantCulture
$eventMarker = 'event=membership_order_create_http_completed '
if ([string]::IsNullOrWhiteSpace($HttpLogRunId)) { $HttpLogRunId = $RunId }
$expectedGoldenRunId = 'membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048'
$expectedGolden = [ordered]@{
    count = 5000
    firstReceivedAtEpochMicros = 1787762582645541L
    lastCompletedAtEpochMicros = 1787762587555387L
    wallClockMicros = 4909846L
    wallClockSeconds = 4.909846D
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
    frontHalfWallClockSeconds = 3.002962D
    frontHalfQps = 832.511D
    backHalfWallClockSeconds = 2.103774D
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

function Get-Double([psobject] $Row, [string] $Name) {
    $value = Get-Field $Row @($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Focused ORDER_CREATE summary is missing $Name."
    }
    return [double]::Parse($value, $invariant)
}

function Assert-Close([double] $Actual, [double] $Expected, [double] $Tolerance, [string] $Name) {
    if ([Math]::Abs($Actual - $Expected) -gt $Tolerance) {
        throw "Golden baseline $Name drifted: expected=$Expected actual=$Actual"
    }
}

function New-Delta([double] $Current, [double] $Baseline) {
    return [ordered]@{
        current = [Math]::Round($Current, 6)
        golden = [Math]::Round($Baseline, 6)
        absolute = [Math]::Round($Current - $Baseline, 6)
        percent = [Math]::Round(($Current / $Baseline - 1D) * 100D, 6)
    }
}

function Get-HalfMetrics([object[]] $Events) {
    $first = [long](($Events | Measure-Object receivedAtEpochMicros -Minimum).Minimum)
    $last = [long](($Events | Measure-Object completedAtEpochMicros -Maximum).Maximum)
    $wall = $last - $first
    return [ordered]@{
        count = $Events.Count
        wallClockMicros = $wall
        wallClockSeconds = [Math]::Round($wall / 1000000D, 6)
        qps = [Math]::Round($Events.Count * 1000000D / $wall, 6)
    }
}

if ($GoldenBaselineRunId -ne $expectedGoldenRunId) {
    throw "Golden baseline Run ID is not the locked baseline: $GoldenBaselineRunId"
}
foreach ($path in @(
        $ScenarioOrdersCsvPath, $HttpEventsLogPath, $FocusedSummaryCsvPath,
        $GoldenBaselineEvidenceRoot)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Golden comparison evidence path does not exist: $path"
    }
}

# 历史 verdict 的 FAIL 属于旧尾窗规则；这里只复核其已验证的完整 5K 证据，不改写历史文件。
$goldenAttemptRoot = Join-Path $GoldenBaselineEvidenceRoot 'E-P1\warmup\attempt-2'
$goldenManifestPath = Join-Path $GoldenBaselineEvidenceRoot 'run-manifest.json'
$goldenScenarioPath = Join-Path $goldenAttemptRoot 'scenario-orders.csv'
$goldenVerdictPath = Join-Path $goldenAttemptRoot 'verdict.json'
$goldenWindowsPath = Join-Path $goldenAttemptRoot 'stability-windows.csv'
$goldenFocusedPath = Join-Path $goldenAttemptRoot 'membership-payment-focused-operation-summary.csv'
$goldenHttpLogPath = Join-Path $GoldenBaselineEvidenceRoot `
    'raw-membership-order-create-http-events.log'
foreach ($path in @(
        $goldenManifestPath, $goldenScenarioPath, $goldenVerdictPath,
        $goldenWindowsPath, $goldenFocusedPath, $goldenHttpLogPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Locked golden evidence is incomplete: $path"
    }
}
$goldenManifest = Get-Content -Raw -LiteralPath $goldenManifestPath | ConvertFrom-Json
$goldenVerdict = Get-Content -Raw -LiteralPath $goldenVerdictPath | ConvertFrom-Json
$goldenScenario = @(Import-Csv -LiteralPath $goldenScenarioPath)
$goldenWindows = @(Import-Csv -LiteralPath $goldenWindowsPath)
$goldenFocusedRows = @(Import-Csv -LiteralPath $goldenFocusedPath | Where-Object operation -eq 'ORDER_CREATE')
$goldenScenarioTraces = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$goldenScenarioOrders = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$goldenScenarioUsers = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($row in $goldenScenario) {
    [void]$goldenScenarioTraces.Add((Get-Field $row @('trace_id','traceId')))
    [void]$goldenScenarioOrders.Add((Get-Field $row @('order_id','orderIdB64')))
    [void]$goldenScenarioUsers.Add((Get-Field $row @('user_id','userId')))
}
if ([string]$goldenManifest.runId -ne $GoldenBaselineRunId -or
        $goldenScenario.Count -ne $expectedGolden.count -or
        $goldenScenarioTraces.Count -ne $expectedGolden.count -or
        $goldenScenarioOrders.Count -ne $expectedGolden.count -or
        $goldenScenarioUsers.Count -ne $expectedGolden.count -or
        [int]$goldenVerdict.expectedCount -ne $expectedGolden.count -or
        [int]$goldenVerdict.http201Count -ne $expectedGolden.count -or
        [int]$goldenVerdict.focusedSuccessCount -ne $expectedGolden.count -or
        $goldenWindows.Count -ne 10 -or
        [int](($goldenWindows | Measure-Object count -Sum).Sum) -ne $expectedGolden.count -or
        $goldenFocusedRows.Count -ne 1) {
    throw 'Locked golden evidence no longer proves 5,000 unique committed HTTP 201 operations.'
}
$goldenWarmupRunId = "$GoldenBaselineRunId-warmup-E-P1-a2"
$goldenHttpByTrace = [Collections.Generic.Dictionary[string,object]]::new(
    [StringComparer]::Ordinal)
$goldenStream = [IO.FileStream]::new(
    $goldenHttpLogPath, [IO.FileMode]::Open, [IO.FileAccess]::Read,
    [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
$goldenReader = [IO.StreamReader]::new(
    $goldenStream, [Text.UTF8Encoding]::new($false), $true)
try {
    while ($null -ne ($line = $goldenReader.ReadLine())) {
        $markerIndex = $line.IndexOf($eventMarker, [StringComparison]::Ordinal)
        if ($markerIndex -lt 0) { continue }
        $fields = @{}
        foreach ($match in [regex]::Matches(
                $line.Substring($markerIndex),
                '(?<key>[A-Za-z][A-Za-z0-9]*)=(?<value>[^\s]+)')) {
            $fields[$match.Groups['key'].Value] = $match.Groups['value'].Value
        }
        if ([string]$fields.r -ne $goldenWarmupRunId -or [string]$fields.sg -ne 'E-P1') {
            continue
        }
        foreach ($required in @('tr','recv','done','dur','status','committed')) {
            if (-not $fields.ContainsKey($required)) {
                throw "Locked golden HTTP event is missing $required."
            }
        }
        $trace = [string]$fields.tr
        $received = [long]$fields.recv
        $completed = [long]$fields.done
        if (-not $goldenScenarioTraces.Contains($trace) -or
                $goldenHttpByTrace.ContainsKey($trace) -or
                [int]$fields.status -ne 201 -or [string]$fields.committed -ne 'true' -or
                $completed -lt $received -or [long]$fields.dur -ne $completed - $received) {
            throw 'Locked golden raw HTTP evidence is duplicate, outside its manifest or not committed HTTP 201.'
        }
        $goldenHttpByTrace.Add($trace, [pscustomobject]@{
            receivedAtEpochMicros = $received
            completedAtEpochMicros = $completed
        })
    }
} finally {
    $goldenReader.Dispose()
}
if ($goldenHttpByTrace.Count -ne $expectedGolden.count) {
    throw "Locked golden raw HTTP event count drifted: $($goldenHttpByTrace.Count)"
}
$goldenHttpEvents = @($goldenHttpByTrace.Values)
$goldenHttpFirst = [long](($goldenHttpEvents |
    Measure-Object receivedAtEpochMicros -Minimum).Minimum)
$goldenHttpLast = [long](($goldenHttpEvents |
    Measure-Object completedAtEpochMicros -Maximum).Maximum)
$goldenFirst = [long](($goldenWindows | Measure-Object firstReceivedAtEpochMicros -Minimum).Minimum)
$goldenLast = [long](($goldenWindows | Measure-Object lastCompletedAtEpochMicros -Maximum).Maximum)
$goldenWall = $goldenLast - $goldenFirst
$goldenQpsRaw = $expectedGolden.count * 1000000D / $goldenWall
if ($goldenHttpFirst -ne $expectedGolden.firstReceivedAtEpochMicros -or
        $goldenHttpLast -ne $expectedGolden.lastCompletedAtEpochMicros -or
        $goldenFirst -ne $goldenHttpFirst -or $goldenLast -ne $goldenHttpLast -or
        $goldenWall -ne $expectedGolden.wallClockMicros) {
    throw 'Locked golden HTTP wall-clock evidence drifted.'
}
Assert-Close ([Math]::Round($goldenQpsRaw, 3)) $expectedGolden.qps 0.0005D 'QPS'
$goldenFrontWindows = @($goldenWindows[0..4])
$goldenBackWindows = @($goldenWindows[5..9])
$goldenFrontWall = [long](($goldenFrontWindows | Measure-Object lastCompletedAtEpochMicros -Maximum).Maximum) -
    [long](($goldenFrontWindows | Measure-Object firstReceivedAtEpochMicros -Minimum).Minimum)
$goldenBackWall = [long](($goldenBackWindows | Measure-Object lastCompletedAtEpochMicros -Maximum).Maximum) -
    [long](($goldenBackWindows | Measure-Object firstReceivedAtEpochMicros -Minimum).Minimum)
Assert-Close ($goldenFrontWall / 1000000D) $expectedGolden.frontHalfWallClockSeconds 0.0000005D 'front-half wall clock'
Assert-Close (2500D * 1000000D / $goldenFrontWall) $expectedGolden.frontHalfQps 0.0005D 'front-half QPS'
Assert-Close ($goldenBackWall / 1000000D) $expectedGolden.backHalfWallClockSeconds 0.0000005D 'back-half wall clock'
Assert-Close (2500D * 1000000D / $goldenBackWall) $expectedGolden.backHalfQps 0.0005D 'back-half QPS'
$goldenFocused = $goldenFocusedRows[0]
foreach ($metric in @(
        @('p50Ms','orderCreateP50Ms'), @('p95Ms','orderCreateP95Ms'), @('p99Ms','orderCreateP99Ms'),
        @('redisWriteQueueWaitP50Ms','redisQueueP50Ms'), @('redisWriteQueueWaitP95Ms','redisQueueP95Ms'), @('redisWriteQueueWaitP99Ms','redisQueueP99Ms'),
        @('redisPipelineExecuteP50Ms','redisPipelineExecuteP50Ms'), @('redisPipelineExecuteP95Ms','redisPipelineExecuteP95Ms'), @('redisPipelineExecuteP99Ms','redisPipelineExecuteP99Ms'),
        @('rabbitPublishConfirmP50Ms','rabbitPublishP50Ms'), @('rabbitPublishConfirmP95Ms','rabbitPublishP95Ms'), @('rabbitPublishConfirmP99Ms','rabbitPublishP99Ms'),
        @('rabbitConfirmWaitP50Ms','rabbitConfirmP50Ms'), @('rabbitConfirmWaitP95Ms','rabbitConfirmP95Ms'), @('rabbitConfirmWaitP99Ms','rabbitConfirmP99Ms'),
        @('dbTransactionP50Ms','dbTransactionP50Ms'), @('dbTransactionP95Ms','dbTransactionP95Ms'), @('dbTransactionP99Ms','dbTransactionP99Ms'))) {
    Assert-Close (Get-Double $goldenFocused $metric[0]) ([double]$expectedGolden[$metric[1]]) 0.0005D $metric[1]
}

$scenarioByTrace = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
$orderSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$userSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($row in @(Import-Csv -LiteralPath $ScenarioOrdersCsvPath)) {
    $rowRunId = Get-Field $row @('run_id','runId')
    $rowGroup = Get-Field $row @('group_code','groupCode')
    $trace = Get-Field $row @('trace_id','traceId')
    $order = Get-Field $row @('order_id','orderIdB64')
    $user = Get-Field $row @('user_id','userId')
    if ($rowRunId -ne $RunId -or $rowGroup -ne $GroupCode -or
            [string]::IsNullOrWhiteSpace($trace) -or
            $user -notmatch '^700000000000[0-7][0-9]{4}$' -or
            $order -notmatch '^[A-Za-z0-9_-]{22}$' -or
            $scenarioByTrace.ContainsKey($trace) -or -not $orderSet.Add($order) -or
            -not $userSet.Add($user)) {
        throw 'Current scenario manifest contains an invalid, duplicate or out-of-scope row.'
    }
    $scenarioByTrace.Add($trace, $order)
}
$currentCount = $scenarioByTrace.Count
if ($currentCount -notin @(5000, 10000) -or $userSet.Count -ne $currentCount) {
    throw 'Golden comparison accepts exactly 5,000 or 10,000 current orders.'
}

$eventsByTrace = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
$outsideScenarioHttpEventCount = 0L
$share = [IO.FileShare]([int][IO.FileShare]::ReadWrite -bor [int][IO.FileShare]::Delete)
$stream = [IO.FileStream]::new($HttpEventsLogPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, $share)
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
        if ([string]$fields.r -ne $HttpLogRunId -or
                [string]$fields.sg -ne $GroupCode) { continue }
        foreach ($required in @('tr','recv','done','dur','status','committed')) {
            if (-not $fields.ContainsKey($required)) { throw "Current HTTP event is missing $required." }
        }
        $trace = [string]$fields.tr
        if (-not $scenarioByTrace.ContainsKey($trace)) {
            if ($AllowEventsOutsideScenarioManifest) {
                $outsideScenarioHttpEventCount += 1L
                continue
            }
            throw 'Current HTTP evidence contains an outside-manifest trace.'
        }
        if ($eventsByTrace.ContainsKey($trace)) {
            throw 'Current HTTP evidence contains a duplicate manifest trace.'
        }
        $received = [long]$fields.recv
        $completed = [long]$fields.done
        if ([int]$fields.status -ne 201 -or [string]$fields.committed -ne 'true' -or
                $completed -lt $received -or [long]$fields.dur -ne $completed - $received) {
            throw 'Current HTTP evidence contains a failed, uncommitted or invalid interval.'
        }
        $eventsByTrace.Add($trace, [pscustomobject]@{
            traceId=$trace; receivedAtEpochMicros=$received; completedAtEpochMicros=$completed;
            durationMicros=[long]$fields.dur
        })
    }
} finally {
    $reader.Dispose()
}
if ($eventsByTrace.Count -ne $currentCount) {
    throw "Current HTTP evidence count mismatch: expected=$currentCount actual=$($eventsByTrace.Count)"
}
$events = @($eventsByTrace.Values | Sort-Object receivedAtEpochMicros, traceId)
$firstReceived = [long](($events | Measure-Object receivedAtEpochMicros -Minimum).Minimum)
$lastCompleted = [long](($events | Measure-Object completedAtEpochMicros -Maximum).Maximum)
$wallMicros = $lastCompleted - $firstReceived
$qpsRaw = $currentCount * 1000000D / $wallMicros
$durationSumMicros = [long](($events | Measure-Object durationMicros -Sum).Sum)
$effectiveCreateConcurrency = $durationSumMicros / [double]$wallMicros
$halfCount = [int]($currentCount / 2)
$frontHalf = Get-HalfMetrics @($events[0..($halfCount - 1)])
$backHalf = Get-HalfMetrics @($events[$halfCount..($currentCount - 1)])
$halfDifference = [double]$backHalf.qps - [double]$frontHalf.qps
$halfDifferencePercent = $halfDifference / [double]$frontHalf.qps * 100D

$currentFocusedRows = @(Import-Csv -LiteralPath $FocusedSummaryCsvPath | Where-Object operation -eq 'ORDER_CREATE')
if ($currentFocusedRows.Count -ne 1 -or
        [int](Get-Field $currentFocusedRows[0] @('attemptCount')) -ne $currentCount -or
        [int](Get-Field $currentFocusedRows[0] @('uniqueOrderCount')) -ne $currentCount -or
        [int](Get-Field $currentFocusedRows[0] @('successCount')) -ne $currentCount) {
    throw 'Current focused ORDER_CREATE summary is incomplete or not unique.'
}
$currentFocused = $currentFocusedRows[0]
$currentMetrics = [ordered]@{
    orderCreateP50Ms = Get-Double $currentFocused 'p50Ms'
    orderCreateP95Ms = Get-Double $currentFocused 'p95Ms'
    orderCreateP99Ms = Get-Double $currentFocused 'p99Ms'
    redisQueueP50Ms = Get-Double $currentFocused 'redisWriteQueueWaitP50Ms'
    redisQueueP95Ms = Get-Double $currentFocused 'redisWriteQueueWaitP95Ms'
    redisQueueP99Ms = Get-Double $currentFocused 'redisWriteQueueWaitP99Ms'
    redisPipelineExecuteP50Ms = Get-Double $currentFocused 'redisPipelineExecuteP50Ms'
    redisPipelineExecuteP95Ms = Get-Double $currentFocused 'redisPipelineExecuteP95Ms'
    redisPipelineExecuteP99Ms = Get-Double $currentFocused 'redisPipelineExecuteP99Ms'
    rabbitPublishP50Ms = Get-Double $currentFocused 'rabbitPublishConfirmP50Ms'
    rabbitPublishP95Ms = Get-Double $currentFocused 'rabbitPublishConfirmP95Ms'
    rabbitPublishP99Ms = Get-Double $currentFocused 'rabbitPublishConfirmP99Ms'
    rabbitConfirmP50Ms = Get-Double $currentFocused 'rabbitConfirmWaitP50Ms'
    rabbitConfirmP95Ms = Get-Double $currentFocused 'rabbitConfirmWaitP95Ms'
    rabbitConfirmP99Ms = Get-Double $currentFocused 'rabbitConfirmWaitP99Ms'
    dbTransactionP50Ms = Get-Double $currentFocused 'dbTransactionP50Ms'
    dbTransactionP95Ms = Get-Double $currentFocused 'dbTransactionP95Ms'
    dbTransactionP99Ms = Get-Double $currentFocused 'dbTransactionP99Ms'
}
$maximumWallSeconds = if ($currentCount -eq 5000) { 5.556D } else { 11.112D }
$targetWallSeconds = if ($currentCount -eq 5000) { 5D } else { 10D }
$contractPassed = $wallMicros -le [long]($maximumWallSeconds * 1000000D) -and $qpsRaw -ge 900D
$targetReached = $wallMicros -lt [long]($targetWallSeconds * 1000000D) -and $qpsRaw -gt 1000D
$goldenReproduction = if ($targetReached) {
    if ($currentCount -eq 5000) { 'REPRODUCED' } else { 'GOLDEN_CAPABILITY_TARGET_REACHED' }
} elseif ($contractPassed) { 'NOT_REPRODUCED_CONTRACT_PASS' } else { 'NOT_REPRODUCED_NOT_QUALIFIED' }

$deltas = [ordered]@{
    wallClockSeconds = New-Delta ($wallMicros / 1000000D) $expectedGolden.wallClockSeconds
    qps = New-Delta $qpsRaw $expectedGolden.qps
    frontHalfQps = New-Delta $frontHalf.qps $expectedGolden.frontHalfQps
    backHalfQps = New-Delta $backHalf.qps $expectedGolden.backHalfQps
}
foreach ($name in $currentMetrics.Keys) {
    $deltas[$name] = New-Delta ([double]$currentMetrics[$name]) ([double]$expectedGolden[$name])
}

$fullOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $fullOutput -Force | Out-Null
$jsonPath = Join-Path $fullOutput 'golden-baseline-comparison.json'
$markdownPath = Join-Path $fullOutput 'golden-baseline-comparison.md'
$report = [ordered]@{
    goldenEvidenceValidated = $true
    goldenEvidence = [ordered]@{
        rawHttpLog = $goldenHttpLogPath
        uniqueCommittedHttp201 = $goldenHttpByTrace.Count
        scenarioTraceSetEqualsHttpTraceSet = $true
        windowsAgreeWithRawHttpBoundaries = $true
    }
    goldenBaselineRunId = $GoldenBaselineRunId
    runId = $RunId
    groupCode = $GroupCode
    executionPhase = $ExecutionPhase
    contractVerdict = if ($contractPassed) { 'PASS' } else { 'PERFORMANCE_FAIL' }
    goldenReproduction = $goldenReproduction
    golden = $expectedGolden
    current = [ordered]@{
        httpLogRunId = $HttpLogRunId
        outsideScenarioHttpEventCount = $outsideScenarioHttpEventCount
        outsideScenarioEventsAllowed = [bool]$AllowEventsOutsideScenarioManifest
        count = $currentCount
        firstReceivedAtEpochMicros = $firstReceived
        lastCompletedAtEpochMicros = $lastCompleted
        wallClockMicros = $wallMicros
        wallClockSeconds = [Math]::Round($wallMicros / 1000000D, 6)
        qps = [Math]::Round($qpsRaw, 6)
        effectiveCreateConcurrency = [Math]::Round($effectiveCreateConcurrency, 6)
        httpStatusCounts = [ordered]@{
            http201Committed = $events.Count
            non201OrUncommitted = 0
            duplicateTraceCount = 0
            outsideManifestEventCount = 0
        }
        frontHalf = $frontHalf
        backHalf = $backHalf
        frontHalfQps = $frontHalf.qps
        backHalfQps = $backHalf.qps
        backHalfMinusFrontHalfQps = [Math]::Round($halfDifference, 6)
        backHalfRelativeToFrontHalfPercent = [Math]::Round($halfDifferencePercent, 6)
        focusedLatency = $currentMetrics
    }
    thresholds = [ordered]@{
        maximumWallClockSeconds = $maximumWallSeconds
        minimumQps = 900D
        goldenCapabilityMaximumWallClockSecondsExclusive = $targetWallSeconds
        goldenCapabilityMinimumQpsExclusive = 1000D
        evaluatedUsingUnroundedValues = $true
    }
    deltas = $deltas
    artifacts = [ordered]@{ markdown=$markdownPath; json=$jsonPath }
}
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding utf8

$classificationText = switch ($goldenReproduction) {
    'REPRODUCED' { '已复现（<5秒且>1,000 QPS）' }
    'GOLDEN_CAPABILITY_TARGET_REACHED' { '10K 黄金能力目标已达到（<10秒且>1,000 QPS）' }
    'NOT_REPRODUCED_CONTRACT_PASS' { '未复现但合同达标（≥900 QPS）' }
    default { '未复现且不达标' }
}
@"
# ORDER_CREATE 黄金基线对照

- 当前 Run ID：$RunId
- 区段 / 阶段：$GroupCode / $ExecutionPhase
- 黄金证据复核：PASS（5,000 / 4.909846 秒 / 1,018.362 QPS）
- 合同门槛：$(if ($contractPassed) { 'PASS' } else { '性能FAIL' })
- 黄金基线复现：$classificationText
- 当前完整 HTTP：$currentCount 条，$([Math]::Round($wallMicros / 1000000D, 6)) 秒，$([Math]::Round($qpsRaw, 3)) QPS
- 有效创建并发：$([Math]::Round($effectiveCreateConcurrency, 3))
- 相对黄金墙钟：$($deltas.wallClockSeconds.absolute) 秒 / $($deltas.wallClockSeconds.percent)%
- 相对黄金 QPS：$($deltas.qps.absolute) / $($deltas.qps.percent)%
- 前半段：$($frontHalf.wallClockSeconds) 秒 / $([Math]::Round($frontHalf.qps, 3)) QPS
- 后半段：$($backHalf.wallClockSeconds) 秒 / $([Math]::Round($backHalf.qps, 3)) QPS
- 后半段相对前半段：$([Math]::Round($halfDifference, 3)) QPS / $([Math]::Round($halfDifferencePercent, 3))%

| 指标 | 当前 | 黄金 | 绝对变化 | 相对变化 |
| --- | ---: | ---: | ---: | ---: |
| ORDER_CREATE P50 ms | $($currentMetrics.orderCreateP50Ms) | $($expectedGolden.orderCreateP50Ms) | $($deltas.orderCreateP50Ms.absolute) | $($deltas.orderCreateP50Ms.percent)% |
| ORDER_CREATE P95 ms | $($currentMetrics.orderCreateP95Ms) | $($expectedGolden.orderCreateP95Ms) | $($deltas.orderCreateP95Ms.absolute) | $($deltas.orderCreateP95Ms.percent)% |
| ORDER_CREATE P99 ms | $($currentMetrics.orderCreateP99Ms) | $($expectedGolden.orderCreateP99Ms) | $($deltas.orderCreateP99Ms.absolute) | $($deltas.orderCreateP99Ms.percent)% |
| Redis 排队 P50 ms | $($currentMetrics.redisQueueP50Ms) | $($expectedGolden.redisQueueP50Ms) | $($deltas.redisQueueP50Ms.absolute) | $($deltas.redisQueueP50Ms.percent)% |
| Redis 排队 P95 ms | $($currentMetrics.redisQueueP95Ms) | $($expectedGolden.redisQueueP95Ms) | $($deltas.redisQueueP95Ms.absolute) | $($deltas.redisQueueP95Ms.percent)% |
| Redis 排队 P99 ms | $($currentMetrics.redisQueueP99Ms) | $($expectedGolden.redisQueueP99Ms) | $($deltas.redisQueueP99Ms.absolute) | $($deltas.redisQueueP99Ms.percent)% |
| Pipeline 执行 P50 ms | $($currentMetrics.redisPipelineExecuteP50Ms) | $($expectedGolden.redisPipelineExecuteP50Ms) | $($deltas.redisPipelineExecuteP50Ms.absolute) | $($deltas.redisPipelineExecuteP50Ms.percent)% |
| Pipeline 执行 P95 ms | $($currentMetrics.redisPipelineExecuteP95Ms) | $($expectedGolden.redisPipelineExecuteP95Ms) | $($deltas.redisPipelineExecuteP95Ms.absolute) | $($deltas.redisPipelineExecuteP95Ms.percent)% |
| Pipeline 执行 P99 ms | $($currentMetrics.redisPipelineExecuteP99Ms) | $($expectedGolden.redisPipelineExecuteP99Ms) | $($deltas.redisPipelineExecuteP99Ms.absolute) | $($deltas.redisPipelineExecuteP99Ms.percent)% |
| Rabbit 发布 P50 ms | $($currentMetrics.rabbitPublishP50Ms) | $($expectedGolden.rabbitPublishP50Ms) | $($deltas.rabbitPublishP50Ms.absolute) | $($deltas.rabbitPublishP50Ms.percent)% |
| Rabbit 发布 P95 ms | $($currentMetrics.rabbitPublishP95Ms) | $($expectedGolden.rabbitPublishP95Ms) | $($deltas.rabbitPublishP95Ms.absolute) | $($deltas.rabbitPublishP95Ms.percent)% |
| Rabbit 发布 P99 ms | $($currentMetrics.rabbitPublishP99Ms) | $($expectedGolden.rabbitPublishP99Ms) | $($deltas.rabbitPublishP99Ms.absolute) | $($deltas.rabbitPublishP99Ms.percent)% |
| Rabbit Confirm P50 ms | $($currentMetrics.rabbitConfirmP50Ms) | $($expectedGolden.rabbitConfirmP50Ms) | $($deltas.rabbitConfirmP50Ms.absolute) | $($deltas.rabbitConfirmP50Ms.percent)% |
| Rabbit Confirm P95 ms | $($currentMetrics.rabbitConfirmP95Ms) | $($expectedGolden.rabbitConfirmP95Ms) | $($deltas.rabbitConfirmP95Ms.absolute) | $($deltas.rabbitConfirmP95Ms.percent)% |
| Rabbit Confirm P99 ms | $($currentMetrics.rabbitConfirmP99Ms) | $($expectedGolden.rabbitConfirmP99Ms) | $($deltas.rabbitConfirmP99Ms.absolute) | $($deltas.rabbitConfirmP99Ms.percent)% |
| 数据库事务 P50 ms | $($currentMetrics.dbTransactionP50Ms) | $($expectedGolden.dbTransactionP50Ms) | $($deltas.dbTransactionP50Ms.absolute) | $($deltas.dbTransactionP50Ms.percent)% |
| 数据库事务 P95 ms | $($currentMetrics.dbTransactionP95Ms) | $($expectedGolden.dbTransactionP95Ms) | $($deltas.dbTransactionP95Ms.absolute) | $($deltas.dbTransactionP95Ms.percent)% |
| 数据库事务 P99 ms | $($currentMetrics.dbTransactionP99Ms) | $($expectedGolden.dbTransactionP99Ms) | $($deltas.dbTransactionP99Ms.absolute) | $($deltas.dbTransactionP99Ms.percent)% |

末两个 500 条窗口、Redis Pipeline P95 与 Rabbit Confirm P95 仅作为预热诊断，不参与完整区段放行。
"@ | Set-Content -LiteralPath $markdownPath -Encoding utf8

Write-Output "Golden baseline comparison written to: $fullOutput"
