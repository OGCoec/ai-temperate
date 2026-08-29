[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $LogPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,128}$')]
    [string] $RunId,
    [ValidatePattern('^$|^[A-Za-z0-9_-]{1,128}$')]
    [string] $HttpLogRunId = '',
    [Parameter(Mandatory = $true)]
    [string] $ScenarioOrdersCsvPath,
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,
    [string] $RequestResultsCsvPath = '',
    [ValidateRange(0D, 1000000D)]
    [double] $MinimumQps = 1000D,
    [ValidateRange(0.001D, 3600D)]
    [double] $MaximumWallClockSeconds = 5D,
    [ValidateRange(0D, 1000000D)]
    [double] $MinimumEffectiveConcurrency = 0D,
    [switch] $RequirePaymentOverlap,
    [switch] $AllowEventsOutsideManifest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$segments = @('E-P1','E-PR','E-A1','E-AR','H-P1','H-PR','H-A1','H-AR')
$eventMarker = 'event=membership_order_create_http_completed '
$tracePattern = '^[A-Za-z0-9_-]{1,128}$'
$orderPattern = '^[A-Za-z0-9_-]{22}$'
$invariant = [Globalization.CultureInfo]::InvariantCulture
if ([string]::IsNullOrWhiteSpace($HttpLogRunId)) { $HttpLogRunId = $RunId }

function Get-Field([psobject] $Row, [string[]] $Names) {
    foreach ($name in $Names) {
        $property = $Row.PSObject.Properties[$name]
        if ($null -ne $property -and -not [string]::IsNullOrWhiteSpace([string]$property.Value)) {
            return [string]$property.Value
        }
    }
    return $null
}

function Get-NearestRank([double[]] $Sorted, [double] $Percentile) {
    if ($Sorted.Count -eq 0) { return $null }
    $index = [Math]::Ceiling($Sorted.Count * $Percentile) - 1
    return [Math]::Round(
        $Sorted[[Math]::Max(0, [Math]::Min($Sorted.Count - 1, $index))], 3)
}

function Get-LatencyStatistics([object[]] $Rows) {
    [double[]] $values = @($Rows | ForEach-Object { [double]$_.durationMicros / 1000D })
    if ($values.Count -eq 0) {
        return [ordered]@{
            averageMs = $null; p50Ms = $null; p95Ms = $null
            p99Ms = $null; maximumMs = $null
        }
    }
    [Array]::Sort($values)
    return [ordered]@{
        averageMs = [Math]::Round(($values | Measure-Object -Average).Average, 3)
        p50Ms = Get-NearestRank $values 0.50
        p95Ms = Get-NearestRank $values 0.95
        p99Ms = Get-NearestRank $values 0.99
        maximumMs = [Math]::Round($values[-1], 3)
    }
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

if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
    throw "Order-create HTTP evidence log does not exist: $LogPath"
}
if (-not (Test-Path -LiteralPath $ScenarioOrdersCsvPath -PathType Leaf)) {
    throw "Order-create scenario manifest does not exist: $ScenarioOrdersCsvPath"
}

$scenarioByTrace = [Collections.Generic.Dictionary[string, psobject]]::new(
    [StringComparer]::Ordinal)
$scenarioOrders = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$scenarioCounts = @{}
foreach ($row in @(Import-Csv -LiteralPath $ScenarioOrdersCsvPath)) {
    $scenarioRun = Get-Field $row @('run_id', 'runId')
    $segment = Get-Field $row @('group_code', 'groupCode', 'segment')
    $traceId = Get-Field $row @('trace_id', 'traceId')
    $orderId = Get-Field $row @('order_id', 'orderIdB64')
    if ($scenarioRun -ne $RunId -or $segment -notin $segments -or
            $traceId -notmatch $tracePattern -or $orderId -notmatch $orderPattern) {
        throw 'Order-create scenario manifest contains an invalid run, segment, trace or order ID.'
    }
    if ($scenarioByTrace.ContainsKey($traceId) -or -not $scenarioOrders.Add($orderId)) {
        throw 'Order-create scenario manifest contains a duplicate trace or order ID.'
    }
    $scenarioByTrace.Add($traceId, [pscustomobject]@{
        segment = $segment
        orderId = $orderId
    })
    if (-not $scenarioCounts.ContainsKey($segment)) {
        $scenarioCounts[$segment] = 0
    }
    $scenarioCounts[$segment] = [int]$scenarioCounts[$segment] + 1
}
$activeSegments = @($segments | Where-Object { $scenarioCounts.ContainsKey($_) })
if ($activeSegments.Count -notin @(1, 8)) {
    throw 'Order-create HTTP report accepts one canary segment or all eight formal segments.'
}
$expectedPerSegment = @($scenarioCounts.Values | Select-Object -Unique)
if ($expectedPerSegment.Count -ne 1 -or $expectedPerSegment[0] -notin @(5000, 10000)) {
    throw 'Order-create HTTP report accepts only eight equal 5,000 or 10,000 row segments.'
}

$fullOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $fullOutput -Force | Out-Null
$eventsPath = Join-Path $fullOutput 'order-create-http-events.log'
$eventWriter = [IO.StreamWriter]::new(
    $eventsPath, $false, [Text.UTF8Encoding]::new($false))
$eventsByTrace = [Collections.Generic.Dictionary[string, psobject]]::new(
    [StringComparer]::Ordinal)
$outsideManifestEventCount = 0L
$logShare = [IO.FileShare]([int][IO.FileShare]::ReadWrite -bor [int][IO.FileShare]::Delete)
$stream = [IO.FileStream]::new(
    $LogPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, $logShare)
$reader = [IO.StreamReader]::new($stream, [Text.UTF8Encoding]::new($false), $true)
try {
    while ($null -ne ($line = $reader.ReadLine())) {
        $markerIndex = $line.IndexOf($eventMarker, [StringComparison]::Ordinal)
        if ($markerIndex -lt 0) { continue }
        $eventLine = $line.Substring($markerIndex)
        $fields = @{}
        foreach ($match in [regex]::Matches(
                $eventLine, '(?<key>[A-Za-z][A-Za-z0-9]*)=(?<value>[^\s]+)')) {
            $fields[$match.Groups['key'].Value] = $match.Groups['value'].Value
        }
        $eventRun = if ($fields.ContainsKey('r')) { $fields.r } else { $fields.runId }
        if ($eventRun -ne $HttpLogRunId) { continue }
        foreach ($required in @('sg','tr','recv','done','dur','status','committed')) {
            if (-not $fields.ContainsKey($required)) {
                throw "Order-create HTTP event is missing field: $required"
            }
        }
        $traceId = [string]$fields.tr
        if (-not $scenarioByTrace.ContainsKey($traceId)) {
            if ($AllowEventsOutsideManifest) {
                $outsideManifestEventCount += 1L
                continue
            }
            throw "Current Run ID contains an HTTP event outside the scenario manifest: $traceId"
        }
        $scenario = $scenarioByTrace[$traceId]
        if ([string]$fields.sg -ne $scenario.segment) {
            throw "Order-create HTTP event segment does not match its trace manifest: $traceId"
        }
        if ($eventsByTrace.ContainsKey($traceId)) {
            throw "Order-create HTTP evidence contains a duplicate trace: $traceId"
        }
        $received = 0L
        $completed = 0L
        $duration = 0L
        $status = 0
        if (-not [long]::TryParse([string]$fields.recv, [ref]$received) -or
                -not [long]::TryParse([string]$fields.done, [ref]$completed) -or
                -not [long]::TryParse([string]$fields.dur, [ref]$duration) -or
                -not [int]::TryParse([string]$fields.status, [ref]$status) -or
                $received -lt 0L -or $completed -lt $received -or
                $duration -ne $completed + (-1L * $received)) {
            throw "Order-create HTTP event contains an invalid time or status: $traceId"
        }
        $committed = [string]$fields.committed -eq 'true'
        $event = [pscustomobject]@{
            segment = [string]$fields.sg
            traceId = $traceId
            orderId = $scenario.orderId
            receivedAtEpochMicros = $received
            completedAtEpochMicros = $completed
            durationMicros = $duration
            status = $status
            committed = $committed
        }
        $eventsByTrace.Add($traceId, $event)
        $eventWriter.WriteLine($eventLine)
    }
} finally {
    $reader.Dispose()
    $eventWriter.Dispose()
}

$paymentRequests = [Collections.Generic.List[object]]::new()
if ($RequirePaymentOverlap -and
        [string]::IsNullOrWhiteSpace($RequestResultsCsvPath)) {
    throw 'Payment-overlap adjudication requires the JMeter request-results CSV.'
}
if (-not [string]::IsNullOrWhiteSpace($RequestResultsCsvPath)) {
    if (-not (Test-Path -LiteralPath $RequestResultsCsvPath -PathType Leaf)) {
        throw "JMeter request-results CSV does not exist: $RequestResultsCsvPath"
    }
    foreach ($row in @(Import-Csv -LiteralPath $RequestResultsCsvPath)) {
        $requestRun = Get-Field $row @('run_id', 'runId')
        $segment = Get-Field $row @('group_code', 'groupCode', 'segment')
        $operation = Get-Field $row @('operation')
        if ($requestRun -ne $RunId -or $segment -notin $activeSegments -or
                $operation -notin @('START_PAYMENT', 'PAYMENT_ATTEMPT')) {
            continue
        }
        $status = 0
        $success = [string]::Equals(
            (Get-Field $row @('success')),
            'true',
            [StringComparison]::OrdinalIgnoreCase)
        if (-not [int]::TryParse((Get-Field $row @('http_status', 'httpStatus')), [ref]$status) -or
                -not $success -or $status -notin @(200, 201)) {
            continue
        }
        $started = Convert-EvidenceTimeToEpochMicros (Get-Field $row @('started_at', 'startedAt'))
        $completed = Convert-EvidenceTimeToEpochMicros (Get-Field $row @('completed_at', 'completedAt'))
        if ($completed -lt $started) {
            throw "Payment request has an invalid time interval in segment $segment."
        }
        $paymentRequests.Add([pscustomobject]@{
            segment = $segment
            startedAtEpochMicros = $started
            completedAtEpochMicros = $completed
        })
    }
}

$segmentVerdicts = [Collections.Generic.List[object]]::new()
$latencySegments = [Collections.Generic.List[object]]::new()
$curveRows = [Collections.Generic.List[object]]::new()
foreach ($segment in $activeSegments) {
    $actual = @($eventsByTrace.Values | Where-Object segment -eq $segment)
    $successful = @($actual | Where-Object { $_.status -eq 201 -and $_.committed })
    $expected = [int]$scenarioCounts[$segment]
    $wallMicros = $null
    $qps = 0D
    $qpsRaw = 0D
    $effectiveCreateConcurrency = 0D
    $firstReceived = $null
    $lastCompleted = $null
    if ($successful.Count -gt 0) {
        $firstReceived = [long](($successful | Measure-Object receivedAtEpochMicros -Minimum).Minimum)
        $lastCompleted = [long](($successful | Measure-Object completedAtEpochMicros -Maximum).Maximum)
        $wallMicros = $lastCompleted - $firstReceived
        if ($wallMicros -gt 0L) {
            $qpsRaw = $successful.Count * 1000000D / $wallMicros
            $qps = [Math]::Round($qpsRaw, 6)
            $durationSumMicros = [long](
                ($successful | Measure-Object durationMicros -Sum).Sum)
            $effectiveCreateConcurrency = [Math]::Round(
                $durationSumMicros / [double]$wallMicros, 3)
        }
    }
    $successfulPayments = @($paymentRequests | Where-Object segment -eq $segment)
    $firstPaymentStarted = if ($successfulPayments.Count -eq 0) { $null } else {
        [long](($successfulPayments |
            Measure-Object startedAtEpochMicros -Minimum).Minimum)
    }
    $paymentOverlap = $successfulPayments.Count -eq $expected -and
        $null -ne $lastCompleted -and
        $null -ne $firstPaymentStarted -and
        $firstPaymentStarted -lt $lastCompleted
    $maximumWallMicros = [long][Math]::Round(
        $MaximumWallClockSeconds * 1000000D,
        [MidpointRounding]::AwayFromZero)
    $passed = $actual.Count -eq $expected -and
        $successful.Count -eq $expected -and
        $null -ne $wallMicros -and
        $wallMicros -le $maximumWallMicros -and
        $qpsRaw -ge $MinimumQps -and
        $effectiveCreateConcurrency -ge $MinimumEffectiveConcurrency -and
        (-not $RequirePaymentOverlap -or $paymentOverlap)
    $segmentVerdicts.Add([pscustomobject][ordered]@{
        segment = $segment
        expectedCount = $expected
        observedTraceCount = $actual.Count
        http201Count = $successful.Count
        firstReceivedAtEpochMicros = $firstReceived
        lastCompletedAtEpochMicros = $lastCompleted
        wallClockMicros = $wallMicros
        wallClockSeconds = if ($null -eq $wallMicros) { $null } else {
            [Math]::Round($wallMicros / 1000000D, 6)
        }
        qps = $qps
        qpsGateValueUnrounded = $qpsRaw
        effectiveCreateConcurrency = $effectiveCreateConcurrency
        successfulPaymentCount = $successfulPayments.Count
        firstPaymentStartedAtEpochMicros = $firstPaymentStarted
        paymentOverlap = $paymentOverlap
        maximumAllowedWallClockMicros = $maximumWallMicros
        verdict = if ($passed) { 'PASS' } else { 'FAIL' }
    })
    $latencySegments.Add([pscustomobject][ordered]@{
        segment = $segment
        http201Count = $successful.Count
        latency = Get-LatencyStatistics $successful
    })

    $curve = [Collections.Generic.SortedDictionary[long, int[]]]::new()
    foreach ($event in $successful) {
        foreach ($point in @(
                @([long]$event.receivedAtEpochMicros, 1, 0),
                @([long]$event.completedAtEpochMicros, -1, 0))) {
            if (-not $curve.ContainsKey($point[0])) { $curve[$point[0]] = @(0, 0) }
            $curve[$point[0]][0] += $point[1]
        }
    }
    foreach ($payment in $successfulPayments) {
        foreach ($point in @(
                @([long]$payment.startedAtEpochMicros, 0, 1),
                @([long]$payment.completedAtEpochMicros, 0, -1))) {
            if (-not $curve.ContainsKey($point[0])) { $curve[$point[0]] = @(0, 0) }
            $curve[$point[0]][1] += $point[2]
        }
    }
    $activeCreate = 0
    $activePayment = 0
    foreach ($point in $curve.GetEnumerator()) {
        $activeCreate += $point.Value[0]
        $activePayment += $point.Value[1]
        $curveRows.Add([pscustomobject][ordered]@{
            segment = $segment
            epochMicros = $point.Key
            activeCreate = $activeCreate
            activePayment = $activePayment
        })
    }
}

$qpsPath = Join-Path $fullOutput 'order-create-segment-qps.csv'
$segmentVerdicts | Export-Csv -LiteralPath $qpsPath -NoTypeInformation -Encoding utf8
$latencyPath = Join-Path $fullOutput 'order-create-segment-latency.json'
([ordered]@{
    runId = $RunId
    httpLogRunId = $HttpLogRunId
    generatedAt = [datetimeoffset]::UtcNow.ToString('o')
    segments = $latencySegments
}) | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $latencyPath -Encoding utf8
$curvePath = Join-Path $fullOutput 'order-create-payment-concurrency-curve.csv'
$curveRows | Export-Csv -LiteralPath $curvePath -NoTypeInformation -Encoding utf8
$overall = if (@($segmentVerdicts | Where-Object verdict -ne 'PASS').Count -eq 0) {
    'PASS'
} else { 'FAIL' }
$verdictPath = Join-Path $fullOutput 'order-create-http-verdict.json'
([ordered]@{
    runId = $RunId
    httpLogRunId = $HttpLogRunId
    generatedAt = [datetimeoffset]::UtcNow.ToString('o')
    scale = if ($activeSegments.Count -eq 1) { 'CANARY_5K' } elseif (
            $expectedPerSegment[0] -eq 5000) { 'PERFORMANCE_40K' } else { 'CAPACITY_80K' }
    expectedRowsPerSegment = $expectedPerSegment[0]
    scenarioTraceCount = $scenarioByTrace.Count
    observedTraceCount = $eventsByTrace.Count
    outsideManifestEventCount = $outsideManifestEventCount
    outsideManifestEventsAllowed = [bool]$AllowEventsOutsideManifest
    minimumQps = $MinimumQps
    evaluatedUsingUnroundedQps = $true
    maximumWallClockSeconds = $MaximumWallClockSeconds
    minimumEffectiveConcurrency = $MinimumEffectiveConcurrency
    requirePaymentOverlap = [bool]$RequirePaymentOverlap
    concurrency200Verdict = if ($MinimumEffectiveConcurrency -ge 200D) {
        $overall
    } else {
        'NOT_EVALUATED'
    }
    verdict = $overall
    segments = $segmentVerdicts
    artifacts = [ordered]@{
        eventsLog = $eventsPath
        qpsCsv = $qpsPath
        latencyJson = $latencyPath
        concurrencyCurveCsv = $curvePath
    }
}) | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $verdictPath -Encoding utf8

Write-Output "Membership order-create HTTP report written to: $fullOutput"
