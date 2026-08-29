[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]] $LogPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,128}$')]
    [string] $RunId,
    [ValidatePattern('^$|^[A-Za-z0-9_-]{1,128}$')]
    [string] $LogRunId = '',
    [Parameter(Mandatory = $true)]
    [string] $ScenarioOrdersCsvPath,
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,
    [ValidateRange(1, 1000)]
    [int] $TopSlowCount = 100,
    [long] $MinimumCompletedAtEpochMs = 0,
    [switch] $AllowEventsOutsideScenarioManifest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$invariantCulture = [Globalization.CultureInfo]::InvariantCulture
$eventMarker = 'event=membership_payment_operation_completed '
$orderIdPattern = '^[A-Za-z0-9_-]{22}$'
$focusedOperations = @('ORDER_CREATE', 'PAYMENT_ATTEMPT')
$resolvedLogRunId = if ([string]::IsNullOrWhiteSpace($LogRunId)) { $RunId } else { $LogRunId }
$fieldAliases = [ordered]@{
    runId = 'r'; operation = 'op'; traceId = 'tr'; messageId = 'mid'
    orderIdB64 = 'oid'; flow = 'fl'; stageIndex = 'si'; outcome = 'out'
    completedAtEpochMs = 'end'; totalMs = 't'; applicationMs = 'app'
    redisOrderWriteMs = 'row'; redisProviderWriteMs = 'rpw'; redisOrderMs = 'ro'
    redisWritePermitWaitMs = 'rwp'; redisWriteQueueWaitMs = 'rwq'
    redisPipelineBatchWaitMs = 'rwb'; redisPipelineExecuteMs = 'rwe'
    redisWriteDispatchMs = 'rwd'; redisPipelineBatchSize = 'rwsz'
    redisPipelineLane = 'rwl'
    redisTransitionMs = 'rt'; otherRedisMs = 'or'; rabbitPublishConfirmMs = 'rpc'
    rabbitPublishSubmitMs = 'rps'; rabbitConfirmWaitMs = 'rcw'
    rabbitSubmissionSize = 'rpsz'; dbTransactionMs = 'dbt'
    markerMs = 'mk'; dbMs = 'db'; ackMs = 'ack'; ackAction = 'aa'
    barRefundMs = 'br'; errorClass = 'err'; deliveryCount = 'dc'
    queueAgeMs = 'qa'; deliveryOverdueMs = 'do'; barQueryMs = 'bq'; barCloseMs = 'bc'
}
if ($MinimumCompletedAtEpochMs -lt 0L) {
    throw 'MinimumCompletedAtEpochMs must be non-negative.'
}
$durationFields = @(
    'totalMs', 'applicationMs', 'redisOrderWriteMs', 'redisWritePermitWaitMs',
    'redisWriteQueueWaitMs', 'redisPipelineBatchWaitMs', 'redisPipelineExecuteMs',
    'redisWriteDispatchMs', 'redisProviderWriteMs', 'redisOrderMs',
    'redisTransitionMs', 'otherRedisMs', 'rabbitPublishConfirmMs', 'markerMs',
    'rabbitPublishSubmitMs', 'rabbitConfirmWaitMs', 'dbMs', 'dbTransactionMs',
    'ackMs', 'barRefundMs', 'barQueryMs', 'barCloseMs')
$breakdownFields = @(
    'redisPipelineBatchSize', 'redisPipelineLane', 'rabbitSubmissionSize')
$eventColumns = @(
    'runId', 'groupCode', 'operation', 'traceId', 'messageId', 'orderIdB64',
    'flow', 'stageIndex', 'deliveryCount', 'queueAgeMs', 'deliveryOverdueMs',
    'outcome', 'completedAtEpochMs') +
    $durationFields + $breakdownFields + @('ackAction', 'errorClass')
$requiredFields = @(
    'runId', 'operation', 'traceId', 'messageId', 'flow',
    'stageIndex', 'outcome', 'completedAtEpochMs', 'ackAction', 'errorClass') +
    $durationFields

function Get-FieldValue {
    param(
        [Parameter(Mandatory = $true)] [psobject] $Record,
        [Parameter(Mandatory = $true)] [string] $Name,
        [object] $Fallback = $null
    )
    $property = $Record.PSObject.Properties[$Name]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        return $Fallback
    }
    return $property.Value
}

function Get-FirstFieldValue {
    param(
        [Parameter(Mandatory = $true)] [psobject] $Record,
        [Parameter(Mandatory = $true)] [string[]] $Names
    )
    foreach ($name in $Names) {
        $value = Get-FieldValue -Record $Record -Name $name
        if ($null -ne $value) { return [string]$value }
    }
    return $null
}

function Convert-ToMilliseconds {
    param(
        [Parameter(Mandatory = $true)] [object] $Value,
        [Parameter(Mandatory = $true)] [string] $FieldName
    )
    $parsed = 0D
    if (-not [double]::TryParse(
            [string]$Value,
            [Globalization.NumberStyles]::Float,
            $invariantCulture,
            [ref]$parsed) -or
            -not [double]::IsFinite($parsed) -or $parsed -lt 0D) {
        throw "Focused timing field is not a non-negative finite number: $FieldName=$Value"
    }
    return $parsed
}

function Convert-ToBreakdownValue {
    param(
        [Parameter(Mandatory = $true)] [object] $Value,
        [Parameter(Mandatory = $true)] [string] $FieldName
    )
    if ($FieldName -eq 'redisPipelineLane' -and [string]$Value -eq 'unavailable') {
        return 'unavailable'
    }
    $parsed = 0
    if (-not [int]::TryParse([string]$Value, [ref]$parsed)) {
        throw "Focused timing breakdown field is not an integer: $FieldName=$Value"
    }
    $valid = switch ($FieldName) {
        'redisPipelineBatchSize' { $parsed -ge 0 -and $parsed -le 64 }
        'redisPipelineLane' { $parsed -ge 0 -and $parsed -le 5 }
        'rabbitSubmissionSize' { $parsed -eq 0 -or $parsed -eq 1 }
        default { $false }
    }
    if (-not $valid) {
        throw "Focused timing breakdown field is outside its contract: $FieldName=$Value"
    }
    return [string]$parsed
}

function Format-Milliseconds([AllowNull()] [object] $Value) {
    if ($null -eq $Value) { return '' }
    return ([double]$Value).ToString('F3', $invariantCulture)
}

function ConvertTo-CsvLine([object[]] $Values) {
    return (@($Values | ForEach-Object {
        '"' + ([string]$_).Replace('"', '""') + '"'
    }) -join ',')
}

function New-AggregateState {
    $durations = @{}
    foreach ($fieldName in $durationFields) {
        $durations[$fieldName] = [Collections.Generic.List[double]]::new()
    }
    return @{
        attemptCount = 0L
        uniqueOrders = [Collections.Generic.HashSet[string]]::new(
            [StringComparer]::Ordinal)
        outcomes = @{ SUCCESS = 0L; ACKED = 0L; NACKED = 0L; FAILED = 0L }
        durations = $durations
    }
}

function Get-Percentile {
    param([double[]] $SortedValues, [double] $Percentile)
    if ($SortedValues.Count -eq 0) { return $null }
    $index = [Math]::Ceiling($SortedValues.Count * $Percentile) - 1
    $index = [Math]::Max(0, [Math]::Min($SortedValues.Count - 1, $index))
    return [Math]::Round($SortedValues[$index], 3)
}

function New-Statistics([Collections.Generic.List[double]] $Values) {
    if ($Values.Count -eq 0) {
        return [pscustomobject]@{
            average = $null; p50 = $null; p95 = $null; p99 = $null; maximum = $null
        }
    }
    [double[]]$sorted = $Values.ToArray()
    [Array]::Sort($sorted)
    return [pscustomobject]@{
        average = [Math]::Round(($Values | Measure-Object -Average).Average, 3)
        p50 = Get-Percentile $sorted 0.50
        p95 = Get-Percentile $sorted 0.95
        p99 = Get-Percentile $sorted 0.99
        maximum = [Math]::Round($sorted[-1], 3)
    }
}

function New-OperationSummary([string] $Operation, [hashtable] $State) {
    $total = New-Statistics $State.durations['totalMs']
    $row = [ordered]@{
        operation = $Operation
        attemptCount = $State.attemptCount
        uniqueOrderCount = $State.uniqueOrders.Count
        successCount = $State.outcomes.SUCCESS
        ackedCount = $State.outcomes.ACKED
        nackedCount = $State.outcomes.NACKED
        failedCount = $State.outcomes.FAILED
        averageMs = Format-Milliseconds $total.average
        p50Ms = Format-Milliseconds $total.p50
        p95Ms = Format-Milliseconds $total.p95
        p99Ms = Format-Milliseconds $total.p99
        maximumMs = Format-Milliseconds $total.maximum
        atLeast1000MsCount = @($State.durations['totalMs'] | Where-Object { $_ -ge 1000D }).Count
    }
    foreach ($fieldName in @($durationFields | Where-Object { $_ -ne 'totalMs' })) {
        $statistics = New-Statistics $State.durations[$fieldName]
        $prefix = $fieldName.Substring(0, $fieldName.Length - 2)
        $row[$prefix + 'AverageMs'] = Format-Milliseconds $statistics.average
        $row[$prefix + 'P50Ms'] = Format-Milliseconds $statistics.p50
        $row[$prefix + 'P95Ms'] = Format-Milliseconds $statistics.p95
        $row[$prefix + 'P99Ms'] = Format-Milliseconds $statistics.p99
        $row[$prefix + 'MaximumMs'] = Format-Milliseconds $statistics.maximum
    }
    return [pscustomobject]$row
}

if (-not (Test-Path -LiteralPath $ScenarioOrdersCsvPath -PathType Leaf)) {
    throw "Scenario order CSV does not exist: $ScenarioOrdersCsvPath"
}
$scenarioByOrder = [Collections.Generic.Dictionary[string, string]]::new(
    [StringComparer]::Ordinal)
foreach ($scenario in @(Import-Csv -LiteralPath $ScenarioOrdersCsvPath)) {
    $orderId = Get-FirstFieldValue $scenario @('order_id', 'orderIdB64', 'order_id_b64')
    $groupCode = Get-FirstFieldValue $scenario @('group_code', 'groupCode')
    if ($orderId -notmatch $orderIdPattern -or [string]::IsNullOrWhiteSpace($groupCode)) {
        throw 'Scenario order CSV contains an invalid order ID or group code.'
    }
    if ($scenarioByOrder.ContainsKey($orderId)) {
        throw "Scenario order CSV contains a duplicate order ID: $orderId"
    }
    $scenarioByOrder.Add($orderId, $groupCode)
}
if ($scenarioByOrder.Count -eq 0) { throw 'Scenario order CSV is empty.' }

$resolvedPaths = [Collections.Generic.List[string]]::new()
foreach ($pathExpression in $LogPath) {
    foreach ($item in @(Get-Item -Path $pathExpression -ErrorAction Stop |
            Where-Object { -not $_.PSIsContainer })) {
        $resolvedPaths.Add($item.FullName)
    }
}
if ($resolvedPaths.Count -eq 0) { throw 'No membership payment timing log files were found.' }

$fullOutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $fullOutputDirectory -Force | Out-Null
$eventsPath = Join-Path $fullOutputDirectory 'membership-payment-focused-events.csv'
$summaryPath = Join-Path $fullOutputDirectory `
    'membership-payment-focused-operation-summary.csv'
$topPath = Join-Path $fullOutputDirectory 'membership-payment-focused-top-100.csv'
$diagnosticsPath = Join-Path $fullOutputDirectory `
    'membership-payment-slow-failure-diagnostics.csv'
$jsonPath = Join-Path $fullOutputDirectory 'membership-payment-focused-report.json'
$markdownPath = Join-Path $fullOutputDirectory 'membership-payment-focused-report.md'
$temporaryEventsPath = $eventsPath + '.tmp-' + [guid]::NewGuid().ToString('N')
$temporaryDiagnosticsPath = $diagnosticsPath + '.tmp-' + [guid]::NewGuid().ToString('N')

$aggregateByOperation = @{}
foreach ($operation in $focusedOperations) { $aggregateByOperation[$operation] = New-AggregateState }
$topQueue = [Collections.Generic.PriorityQueue[psobject, double]]::new()
$eventCount = 0L
$diagnosticEventCount = 0L
$outsideScenarioFocusedEventCount = 0L
$runLogBytes = 0L
$encoding = [Text.UTF8Encoding]::new($false)
$writer = [IO.StreamWriter]::new($temporaryEventsPath, $false, $encoding)
$diagnosticWriter = [IO.StreamWriter]::new(
    $temporaryDiagnosticsPath, $false, $encoding)
$completed = $false
try {
    $writer.WriteLine((ConvertTo-CsvLine $eventColumns))
    $diagnosticWriter.WriteLine((ConvertTo-CsvLine $eventColumns))
    foreach ($path in $resolvedPaths) {
        # Windows 会双向检查共享模式；报告必须允许仍在运行的应用继续追加正式时序日志。
        $logShare = [IO.FileShare]([int][IO.FileShare]::ReadWrite -bor
            [int][IO.FileShare]::Delete)
        $logStream = [IO.FileStream]::new(
            $path,
            [IO.FileMode]::Open,
            [IO.FileAccess]::Read,
            $logShare)
        $reader = $null
        try {
            $reader = [IO.StreamReader]::new($logStream, $encoding, $true)
            while ($null -ne ($line = $reader.ReadLine())) {
                $eventIndex = $line.IndexOf($eventMarker, [StringComparison]::Ordinal)
                if ($eventIndex -lt 0) { continue }
                $fields = [ordered]@{}
                foreach ($match in [regex]::Matches(
                        $line.Substring($eventIndex),
                        '(?<key>[A-Za-z][A-Za-z0-9]*)=(?<value>[^\s]+)')) {
                    $fields[$match.Groups['key'].Value] = $match.Groups['value'].Value
                }
                foreach ($canonicalName in $fieldAliases.Keys) {
                    $alias = $fieldAliases[$canonicalName]
                    if (-not $fields.Contains($canonicalName) -and $fields.Contains($alias)) {
                        $fields[$canonicalName] = $fields[$alias]
                    }
                }
                if (-not $fields.Contains('runId') -or
                        $fields['runId'] -ne $resolvedLogRunId) { continue }
                if (-not $fields.Contains('operation')) {
                    throw 'Current Run ID contains a timing event without an operation.'
                }
                foreach ($fieldName in $durationFields) {
                    if (-not $fields.Contains($fieldName)) { $fields[$fieldName] = '0.000' }
                }
                foreach ($default in @{
                        traceId = 'unavailable'; messageId = 'unavailable'; flow = 'none'
                        stageIndex = 'unavailable'; deliveryCount = '0'; queueAgeMs = 'unavailable'
                        deliveryOverdueMs = 'unavailable'; ackAction = 'none'; errorClass = 'none'
                        redisPipelineBatchSize = '0'; redisPipelineLane = 'unavailable'
                        rabbitSubmissionSize = '0'
                    }.GetEnumerator()) {
                    if (-not $fields.Contains($default.Key)) {
                        $fields[$default.Key] = $default.Value
                    }
                }
                foreach ($fieldName in $requiredFields) {
                    if (-not $fields.Contains($fieldName)) {
                        throw "Focused timing event is missing required field: $fieldName"
                    }
                }
                $completedAtEpochMs = 0L
                if (-not [long]::TryParse(
                        [string]$fields['completedAtEpochMs'],
                        [Globalization.NumberStyles]::Integer,
                        $invariantCulture,
                        [ref]$completedAtEpochMs) -or $completedAtEpochMs -lt 0L) {
                    throw "Focused timing event contains an invalid completedAtEpochMs: $($fields['completedAtEpochMs'])"
                }
                if ($completedAtEpochMs -lt $MinimumCompletedAtEpochMs) { continue }
                $runLogBytes += $encoding.GetByteCount($line) + 1L
                $operation = [string]$fields['operation']
                $outcome = [string]$fields['outcome']
                if ($operation -notin $focusedOperations) {
                    $totalMs = Convert-ToMilliseconds $fields['totalMs'] 'totalMs'
                    $isDiagnostic = $totalMs -ge 1000D -or
                        $outcome -in @('FAILED', 'NACKED') -or
                        [string]$fields['ackAction'] -eq 'NACK' -or
                        [long]$fields['deliveryCount'] -gt 0L
                    if (-not $isDiagnostic) {
                        throw "Current Run ID contains an unexpected fast success outside the two forced operations: $operation"
                    }
                    $diagnosticOrderId = [string]$fields['orderIdB64']
                    $diagnosticGroup = if ($diagnosticOrderId -match $orderIdPattern -and
                            $scenarioByOrder.ContainsKey($diagnosticOrderId)) {
                        $scenarioByOrder[$diagnosticOrderId]
                    } else { 'unavailable' }
                    $diagnosticEvent = [ordered]@{
                        runId = $RunId; groupCode = $diagnosticGroup
                        operation = $operation; traceId = $fields['traceId']
                        messageId = $fields['messageId']; orderIdB64 = $diagnosticOrderId
                        flow = $fields['flow']; stageIndex = $fields['stageIndex']
                        deliveryCount = $fields['deliveryCount']
                        queueAgeMs = $fields['queueAgeMs']
                        deliveryOverdueMs = $fields['deliveryOverdueMs']
                        outcome = $outcome; completedAtEpochMs = $fields['completedAtEpochMs']
                    }
                    foreach ($fieldName in $durationFields) {
                        $diagnosticEvent[$fieldName] = Format-Milliseconds `
                            (Convert-ToMilliseconds $fields[$fieldName] $fieldName)
                    }
                    foreach ($fieldName in $breakdownFields) {
                        $diagnosticEvent[$fieldName] = Convert-ToBreakdownValue `
                            $fields[$fieldName] $fieldName
                    }
                    $diagnosticEvent['ackAction'] = $fields['ackAction']
                    $diagnosticEvent['errorClass'] = $fields['errorClass']
                    $diagnosticWriter.WriteLine((ConvertTo-CsvLine @(
                        $eventColumns | ForEach-Object { $diagnosticEvent[$_] })))
                    $diagnosticEventCount += 1L
                    continue
                }
                $orderId = if ($fields.Contains('orderIdB64')) {
                    [string]$fields['orderIdB64']
                } else { '' }
                if ([string]::IsNullOrWhiteSpace($orderId)) {
                    # TEAM 负向探针会在订单生成前受控失败；它属于诊断证据，不能污染正式订单统计。
                    if ($outcome -notin @('FAILED', 'NACKED')) {
                        throw "Focused timing success is missing its Base64URL order ID: $operation"
                    }
                    $diagnosticEvent = [ordered]@{
                        runId = $RunId; groupCode = 'unavailable'
                        operation = $operation; traceId = $fields['traceId']
                        messageId = $fields['messageId']; orderIdB64 = ''
                        flow = $fields['flow']; stageIndex = $fields['stageIndex']
                        deliveryCount = $fields['deliveryCount']
                        queueAgeMs = $fields['queueAgeMs']
                        deliveryOverdueMs = $fields['deliveryOverdueMs']
                        outcome = $outcome; completedAtEpochMs = $fields['completedAtEpochMs']
                    }
                    foreach ($fieldName in $durationFields) {
                        $diagnosticEvent[$fieldName] = Format-Milliseconds `
                            (Convert-ToMilliseconds $fields[$fieldName] $fieldName)
                    }
                    foreach ($fieldName in $breakdownFields) {
                        $diagnosticEvent[$fieldName] = Convert-ToBreakdownValue `
                            $fields[$fieldName] $fieldName
                    }
                    $diagnosticEvent['ackAction'] = $fields['ackAction']
                    $diagnosticEvent['errorClass'] = $fields['errorClass']
                    $diagnosticWriter.WriteLine((ConvertTo-CsvLine @(
                        $eventColumns | ForEach-Object { $diagnosticEvent[$_] })))
                    $diagnosticEventCount += 1L
                    continue
                }
                if ($orderId -notmatch $orderIdPattern) {
                    throw "Focused timing event contains an invalid Base64URL order ID: $orderId"
                }
                if (-not $scenarioByOrder.ContainsKey($orderId)) {
                    if ($AllowEventsOutsideScenarioManifest) {
                        # 同一应用 PID 的日志流同时包含本子 Run 的真实预热；最终正式汇总只排除并计数这些独立清单事件。
                        $outsideScenarioFocusedEventCount += 1L
                        continue
                    }
                    throw "Focused timing event cannot be linked to the scenario manifest: $orderId"
                }
                $state = $aggregateByOperation[$operation]
                $state.attemptCount = [long]$state.attemptCount + 1L
                [void]$state.uniqueOrders.Add($orderId)
                if (-not $state.outcomes.ContainsKey($outcome)) {
                    throw "Focused timing event contains an unsupported outcome: $outcome"
                }
                $state.outcomes[$outcome] = [long]$state.outcomes[$outcome] + 1L

                $event = [ordered]@{
                    runId = $RunId; groupCode = $scenarioByOrder[$orderId]
                    operation = $operation; traceId = $fields['traceId']
                    messageId = $fields['messageId']; orderIdB64 = $orderId
                    flow = $fields['flow']; stageIndex = $fields['stageIndex']
                    deliveryCount = $fields['deliveryCount']
                    queueAgeMs = $fields['queueAgeMs']
                    deliveryOverdueMs = $fields['deliveryOverdueMs']
                    outcome = $outcome; completedAtEpochMs = $fields['completedAtEpochMs']
                }
                foreach ($fieldName in $durationFields) {
                    $value = Convert-ToMilliseconds $fields[$fieldName] $fieldName
                    $state.durations[$fieldName].Add($value)
                    $event[$fieldName] = Format-Milliseconds $value
                }
                foreach ($fieldName in $breakdownFields) {
                    $event[$fieldName] = Convert-ToBreakdownValue `
                        $fields[$fieldName] $fieldName
                }
                $event['ackAction'] = $fields['ackAction']
                $event['errorClass'] = $fields['errorClass']
                $eventObject = [pscustomobject]$event
                $writer.WriteLine((ConvertTo-CsvLine @(
                    $eventColumns | ForEach-Object { $event[$_] })))
                $topQueue.Enqueue($eventObject, [double]$event['totalMs'])
                if ($topQueue.Count -gt $TopSlowCount) { [void]$topQueue.Dequeue() }
                $eventCount += 1L
            }
        } finally {
            if ($null -ne $reader) {
                $reader.Dispose()
            } else {
                $logStream.Dispose()
            }
        }
    }
    if ($eventCount -eq 0) {
        throw "No focused membership payment timing events were found for log Run ID $resolvedLogRunId."
    }
    $completed = $true
} finally {
    $writer.Dispose()
    $diagnosticWriter.Dispose()
    if (-not $completed) {
        Remove-Item -LiteralPath $temporaryEventsPath -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $temporaryDiagnosticsPath -Force -ErrorAction SilentlyContinue
    }
}
Move-Item -LiteralPath $temporaryEventsPath -Destination $eventsPath -Force
Move-Item -LiteralPath $temporaryDiagnosticsPath -Destination $diagnosticsPath -Force

$operationSummary = @($focusedOperations | ForEach-Object {
    New-OperationSummary $_ $aggregateByOperation[$_]
})
$operationSummary | Export-Csv -LiteralPath $summaryPath -NoTypeInformation -Encoding UTF8
$topRows = @($topQueue.UnorderedItems | ForEach-Object { $_.Item1 } |
    Sort-Object { [double]$_.totalMs } -Descending)
$topRows | Export-Csv -LiteralPath $topPath -NoTypeInformation -Encoding UTF8

$report = [ordered]@{
    runId = $RunId
    logRunId = $resolvedLogRunId
    generatedAtUtc = [datetimeoffset]::UtcNow.ToString('O')
    eventCount = $eventCount
    diagnosticEventCount = $diagnosticEventCount
    outsideScenarioFocusedEventCount = $outsideScenarioFocusedEventCount
    outsideScenarioEventsAllowed = [bool]$AllowEventsOutsideScenarioManifest
    runLogBytes = $runLogBytes
    runLogMiB = [Math]::Round($runLogBytes / 1MB, 3)
    logVolumeTargetMiB = [ordered]@{ minimum = 19; center = 24; maximum = 28 }
    logVolumeTargetMet = $runLogBytes -ge (19MB) -and $runLogBytes -le (28MB)
    scenarioOrderCount = $scenarioByOrder.Count
    minimumCompletedAtEpochMs = $MinimumCompletedAtEpochMs
    focusedOperations = $focusedOperations
    operations = $operationSummary
    oldOrderCreateAnchorsMs = @(3513.624, 10940.083, 11968.412)
    oldAnchorBoundary = 'verified individual samples only; not an old average or percentile distribution'
    measurementBoundary = [ordered]@{
        javaRedisWait = 'Java-side client scheduling, connection/event-loop queueing, server queueing, execution and response handling'
        redisSlowlog = 'Redis server-side command or Lua execution time only'
        logging = 'ORDER_CREATE and PAYMENT_ATTEMPT are always logged; all other operations are retained only when slow, failed or NACKed'
    }
    artifacts = [ordered]@{
        eventsCsv = $eventsPath; operationSummaryCsv = $summaryPath
        top100Csv = $topPath; diagnosticsCsv = $diagnosticsPath
        markdownReport = $markdownPath
    }
}
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$markdownLines = [Collections.Generic.List[string]]::new()
$markdownLines.Add('# 会员支付第二轮 HTTP 主链路与慢请求诊断报告')
$markdownLines.Add('')
$markdownLines.Add("- Run ID：$RunId")
$markdownLines.Add("- 日志 Run ID：$resolvedLogRunId")
$markdownLines.Add("- 场景订单数：$($scenarioByOrder.Count)")
$markdownLines.Add("- 两个 HTTP 主操作事件总数：$eventCount")
$markdownLines.Add("- 其他慢请求、失败或 NACK 诊断事件总数：$diagnosticEventCount")
$markdownLines.Add("- 最终聚合中排除的独立预热主操作事件：$outsideScenarioFocusedEventCount")
$markdownLines.Add("- 本 Run ID 原始日志体积：$([Math]::Round($runLogBytes / 1MB, 3)) MiB（目标 19～28 MiB，中心约 24 MiB）")
$markdownLines.Add('')
$markdownLines.Add('> ORDER_CREATE、PAYMENT_ATTEMPT 全量打印；其他操作只保留超过一秒、FAILED 或 NACK 的诊断记录。主操作统计包含日志开销。')
$markdownLines.Add('')
$markdownLines.Add('## 操作汇总')
$markdownLines.Add('')
$markdownLines.Add('| 操作 | 次数 | 唯一订单 | 平均值 | P50 | P95 | P99 | 最大值 | >=1s | FAILED | NACKED |')
$markdownLines.Add('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
foreach ($row in $operationSummary) {
    $markdownLines.Add(('| {0} | {1} | {2} | {3} ms | {4} ms | {5} ms | {6} ms | {7} ms | {8} | {9} | {10} |' -f
        $row.operation, $row.attemptCount, $row.uniqueOrderCount,
        $row.averageMs, $row.p50Ms, $row.p95Ms, $row.p99Ms,
        $row.maximumMs, $row.atLeast1000MsCount, $row.failedCount, $row.nackedCount))
}
$markdownLines.Add('')
$markdownLines.Add('## 旧轮对比边界')
$markdownLines.Add('')
$markdownLines.Add('- 已确认的旧 ORDER_CREATE 单条锚点：3513.624 ms、10940.083 ms、11968.412 ms。')
$markdownLines.Add('- 这些只是三条已确认慢样本，不是旧轮平均值、P95 或 P99。')
$markdownLines.Add('- Java 端 Redis 分段包含客户端与服务端完整等待；Redis SLOWLOG 只代表服务端命令或 Lua 执行时间，两者必须分开解释。')
$markdownLines.Add('')
$markdownLines.Add('## 关联文件')
$markdownLines.Add('')
$markdownLines.Add('- membership-payment-focused-events.csv')
$markdownLines.Add('- membership-payment-focused-operation-summary.csv')
$markdownLines.Add('- membership-payment-focused-top-100.csv')
$markdownLines.Add('- membership-payment-slow-failure-diagnostics.csv')
$markdownLines.Add('- membership-payment-focused-report.json')
$markdownLines | Set-Content -LiteralPath $markdownPath -Encoding UTF8

Write-Output "Membership payment focused timing artifacts written to: $fullOutputDirectory"
