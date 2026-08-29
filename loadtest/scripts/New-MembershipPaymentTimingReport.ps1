[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]] $LogPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,128}$')]
    [string] $RunId,

    [Parameter(Mandatory = $true)]
    [string] $ScenarioOrdersCsvPath,

    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory,

    [ValidateRange(1, 1000)]
    [int] $TopSlowCount = 100
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture
$orderIdPattern = '^[A-Za-z0-9_-]{22}$'
$eventMarker = 'event=membership_payment_operation_completed '
$stateMachineOperations = @('RABBIT_PENDING', 'RABBIT_CLOSING')
$durationFields = @(
    'totalMs',
    'applicationMs',
    'redisOrderMs',
    'dbMs',
    'markerMs',
    'barQueryMs',
    'barCloseMs',
    'rabbitPublishConfirmMs',
    'ackMs',
    'queueAgeMs',
    'scheduledDelayMs',
    'deliveryOverdueMs'
)

function Get-FieldValue {
    param(
        [Parameter(Mandatory = $true)] [psobject] $Record,
        [Parameter(Mandatory = $true)] [string] $Name,
        [object] $Fallback = $null
    )

    $property = $Record.PSObject.Properties[$Name]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string] $property.Value)) {
        return $Fallback
    }
    return $property.Value
}

function Get-FirstFieldValue {
    param(
        [Parameter(Mandatory = $true)] [psobject] $Record,
        [Parameter(Mandatory = $true)] [string[]] $Names,
        [object] $Fallback = $null
    )

    foreach ($name in $Names) {
        $value = Get-FieldValue -Record $Record -Name $name
        if ($null -ne $value) {
            return $value
        }
    }
    return $Fallback
}

function Get-NumberValues {
    param(
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [object[]] $Records,
        [Parameter(Mandatory = $true)] [string] $Name
    )

    $values = [System.Collections.Generic.List[double]]::new()
    foreach ($record in $Records) {
        $raw = Get-FieldValue -Record $record -Name $Name
        $parsed = 0D
        if ($null -ne $raw -and [double]::TryParse(
                [string] $raw,
                [System.Globalization.NumberStyles]::Float,
                [System.Globalization.CultureInfo]::InvariantCulture,
                [ref] $parsed)) {
            $values.Add($parsed)
        }
    }
    return @($values)
}

function Get-Percentile {
    param(
        [Parameter(Mandatory = $true)] [double[]] $SortedValues,
        [Parameter(Mandatory = $true)] [double] $Percentile
    )

    if ($SortedValues.Count -eq 0) {
        return $null
    }
    $index = [math]::Ceiling($SortedValues.Count * $Percentile) - 1
    $index = [math]::Max(0, [math]::Min($SortedValues.Count - 1, $index))
    return [math]::Round($SortedValues[$index], 3)
}

function New-Statistics {
    param([double[]] $Values)

    if ($null -eq $Values -or $Values.Count -eq 0) {
        return [pscustomobject]@{
            count = 0
            average = $null
            p50 = $null
            p95 = $null
            p99 = $null
            maximum = $null
        }
    }

    [double[]] $sorted = @($Values | Sort-Object)
    return [pscustomobject]@{
        count = $sorted.Count
        average = [math]::Round(($sorted | Measure-Object -Average).Average, 3)
        p50 = Get-Percentile -SortedValues $sorted -Percentile 0.50
        p95 = Get-Percentile -SortedValues $sorted -Percentile 0.95
        p99 = Get-Percentile -SortedValues $sorted -Percentile 0.99
        maximum = [math]::Round($sorted[-1], 3)
    }
}

function Format-Milliseconds {
    param([AllowNull()] [object] $Value)

    if ($null -eq $Value) {
        return ''
    }
    return ([double] $Value).ToString('F3', [System.Globalization.CultureInfo]::InvariantCulture)
}

function New-DurationSummary {
    param([Parameter(Mandatory = $true)] [AllowEmptyCollection()] [object[]] $Records)

    $summary = [ordered]@{}
    foreach ($fieldName in $script:durationFields) {
        [double[]] $values = @(Get-NumberValues -Records $Records -Name $fieldName)
        $summary[$fieldName] = New-Statistics -Values $values
    }
    return [pscustomobject] $summary
}

function New-ThroughputSummary {
    param([Parameter(Mandatory = $true)] [object[]] $Records)

    [double[]] $completedAtValues = @(
        Get-NumberValues -Records $Records -Name 'completedAtEpochMs' | Sort-Object)
    if ($completedAtValues.Count -lt 2) {
        return [pscustomobject]@{
            firstCompletedAtEpochMs = $null
            lastCompletedAtEpochMs = $null
            observationSeconds = $null
            observedCompletionsPerSecond = $null
        }
    }
    $observationSeconds = ($completedAtValues[-1] - $completedAtValues[0]) / 1000D
    return [pscustomobject]@{
        firstCompletedAtEpochMs = [int64] $completedAtValues[0]
        lastCompletedAtEpochMs = [int64] $completedAtValues[-1]
        observationSeconds = [math]::Round($observationSeconds, 3)
        observedCompletionsPerSecond = if ($observationSeconds -gt 0D) {
            [math]::Round($Records.Count / $observationSeconds, 3)
        } else {
            $null
        }
    }
}

function New-StageDefinition {
    param(
        [Parameter(Mandatory = $true)] [string] $Flow,
        [Parameter(Mandatory = $true)] [int] $StageIndex
    )

    return [pscustomobject]@{
        flow = $Flow
        stageIndex = $StageIndex
        label = ('{0} {1}' -f $Flow, $StageIndex)
        key = ('{0}_{1}' -f $Flow, $StageIndex)
    }
}

function Get-StageRecords {
    param(
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [object[]] $Records,
        [Parameter(Mandatory = $true)] [psobject] $Definition
    )

    return @($Records | Where-Object {
        (Get-FieldValue -Record $_ -Name 'flow' -Fallback '') -eq $Definition.flow -and
        [string] (Get-FieldValue -Record $_ -Name 'stageIndex' -Fallback '') -eq
            [string] $Definition.stageIndex
    })
}

function New-StageSummaryRecord {
    param(
        [Parameter(Mandatory = $true)] [psobject] $Definition,
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [object[]] $Records
    )

    $uniqueOrders = @($Records |
        ForEach-Object { Get-FieldValue -Record $_ -Name 'orderIdB64' -Fallback '' } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Sort-Object -Unique)
    $ackCount = @($Records | Where-Object {
        (Get-FieldValue -Record $_ -Name 'outcome' -Fallback '') -eq 'ACKED'
    }).Count
    $nackCount = @($Records | Where-Object {
        (Get-FieldValue -Record $_ -Name 'outcome' -Fallback '') -eq 'NACKED'
    }).Count
    $failedCount = $Records.Count - $ackCount - $nackCount
    $durations = New-DurationSummary -Records $Records

    return [pscustomobject]@{
        stage = $Definition.label
        flow = $Definition.flow
        stageIndex = $Definition.stageIndex
        attemptCount = $Records.Count
        uniqueOrderCount = $uniqueOrders.Count
        averageMs = Format-Milliseconds $durations.totalMs.average
        p50Ms = Format-Milliseconds $durations.totalMs.p50
        p95Ms = Format-Milliseconds $durations.totalMs.p95
        p99Ms = Format-Milliseconds $durations.totalMs.p99
        maximumMs = Format-Milliseconds $durations.totalMs.maximum
        ackCount = $ackCount
        nackCount = $nackCount
        failedCount = $failedCount
        redisOrderAverageMs = Format-Milliseconds $durations.redisOrderMs.average
        redisOrderP95Ms = Format-Milliseconds $durations.redisOrderMs.p95
        redisOrderP99Ms = Format-Milliseconds $durations.redisOrderMs.p99
        dbAverageMs = Format-Milliseconds $durations.dbMs.average
        dbP95Ms = Format-Milliseconds $durations.dbMs.p95
        dbP99Ms = Format-Milliseconds $durations.dbMs.p99
        markerAverageMs = Format-Milliseconds $durations.markerMs.average
        markerP95Ms = Format-Milliseconds $durations.markerMs.p95
        markerP99Ms = Format-Milliseconds $durations.markerMs.p99
        barQueryAverageMs = Format-Milliseconds $durations.barQueryMs.average
        barQueryP95Ms = Format-Milliseconds $durations.barQueryMs.p95
        barQueryP99Ms = Format-Milliseconds $durations.barQueryMs.p99
        barCloseAverageMs = Format-Milliseconds $durations.barCloseMs.average
        barCloseP95Ms = Format-Milliseconds $durations.barCloseMs.p95
        barCloseP99Ms = Format-Milliseconds $durations.barCloseMs.p99
        rabbitConfirmAverageMs = Format-Milliseconds $durations.rabbitPublishConfirmMs.average
        rabbitConfirmP95Ms = Format-Milliseconds $durations.rabbitPublishConfirmMs.p95
        rabbitConfirmP99Ms = Format-Milliseconds $durations.rabbitPublishConfirmMs.p99
        deliveryOverdueAverageMs = Format-Milliseconds $durations.deliveryOverdueMs.average
        deliveryOverdueP95Ms = Format-Milliseconds $durations.deliveryOverdueMs.p95
        deliveryOverdueP99Ms = Format-Milliseconds $durations.deliveryOverdueMs.p99
    }
}

$resolvedPaths = [System.Collections.Generic.List[string]]::new()
foreach ($path in $LogPath) {
    $items = @(Get-Item -Path $path -ErrorAction Stop | Where-Object { -not $_.PSIsContainer })
    foreach ($item in $items) {
        $resolvedPaths.Add($item.FullName)
    }
}
if ($resolvedPaths.Count -eq 0) {
    throw 'No membership payment timing log files were found.'
}
if (-not (Test-Path -LiteralPath $ScenarioOrdersCsvPath -PathType Leaf)) {
    throw ('Scenario order CSV does not exist: {0}' -f $ScenarioOrdersCsvPath)
}

$records = [System.Collections.Generic.List[psobject]]::new()
foreach ($path in $resolvedPaths) {
    foreach ($line in Get-Content -LiteralPath $path) {
        $eventIndex = $line.IndexOf($eventMarker, [System.StringComparison]::Ordinal)
        if ($eventIndex -lt 0) {
            continue
        }
        $fields = [ordered]@{}
        $payload = $line.Substring($eventIndex)
        foreach ($match in [regex]::Matches(
                $payload,
                '(?<key>[A-Za-z][A-Za-z0-9]*)=(?<value>[^\s]+)')) {
            $fields[$match.Groups['key'].Value] = $match.Groups['value'].Value
        }
        if (-not $fields.Contains('runId') -or $fields['runId'] -ne $RunId) {
            continue
        }
        if (-not $fields.Contains('operation') -or -not $fields.Contains('totalMs')) {
            continue
        }
        # 逐订单报告只接收真实 Rabbit 状态机消费，排除创建接口与空回调批次等非阶段事件。
        if ($fields['operation'] -notin $stateMachineOperations) {
            continue
        }
        if (-not $fields.Contains('flow') -or
                $fields['flow'] -notin @('PENDING', 'CLOSING')) {
            continue
        }
        $orderIdB64 = [string] (Get-FieldValue -Record ([pscustomobject] $fields) -Name 'orderIdB64' -Fallback '')
        if ($orderIdB64 -notmatch $orderIdPattern) {
            throw ('Run {0} contains an invalid 22-character Base64URL order ID: {1}' -f
                $RunId, $orderIdB64)
        }
        $records.Add([pscustomobject] $fields)
    }
}
if ($records.Count -eq 0) {
    throw ('No membership payment timing events were found for Run ID {0}.' -f $RunId)
}

$scenarioRows = @(Import-Csv -LiteralPath $ScenarioOrdersCsvPath)
if ($scenarioRows.Count -eq 0) {
    throw 'Scenario order CSV is empty.'
}
$scenarioByOrder = @{}
foreach ($scenarioRow in $scenarioRows) {
    $orderIdB64 = [string] (Get-FirstFieldValue -Record $scenarioRow -Names @(
        'order_id', 'orderIdB64', 'order_id_b64'))
    if ($orderIdB64 -notmatch $orderIdPattern) {
        throw ('Scenario order CSV contains an invalid 22-character Base64URL order ID: {0}' -f
            $orderIdB64)
    }
    if ($scenarioByOrder.ContainsKey($orderIdB64)) {
        throw ('Scenario order CSV contains a duplicate order ID: {0}' -f $orderIdB64)
    }
    $scenarioByOrder[$orderIdB64] = [pscustomobject]@{
        groupCode = [string] (Get-FirstFieldValue -Record $scenarioRow -Names @(
            'group_code', 'groupCode') -Fallback 'UNASSIGNED')
        userId = [string] (Get-FirstFieldValue -Record $scenarioRow -Names @(
            'user_id', 'userId') -Fallback '')
        orderIdB64 = $orderIdB64
    }
}

$stageDefinitions = [System.Collections.Generic.List[psobject]]::new()
foreach ($stageIndex in 0..8) {
    $stageDefinitions.Add((New-StageDefinition -Flow 'PENDING' -StageIndex $stageIndex))
}
foreach ($stageIndex in 0..4) {
    $stageDefinitions.Add((New-StageDefinition -Flow 'CLOSING' -StageIndex $stageIndex))
}

$fullOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath $fullOutputDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $fullOutputDirectory -Force | Out-Null
}
$detailsPath = Join-Path $fullOutputDirectory 'membership-payment-order-stage-details.csv'
$matrixPath = Join-Path $fullOutputDirectory 'membership-payment-order-stage-matrix.csv'
$summaryPath = Join-Path $fullOutputDirectory 'membership-payment-stage-summary.csv'
$jsonPath = Join-Path $fullOutputDirectory 'membership-payment-timing-report.json'
$markdownPath = Join-Path $fullOutputDirectory 'membership-payment-test-report.md'

$details = @($records | Sort-Object {
        [int64] (Get-FieldValue -Record $_ -Name 'completedAtEpochMs' -Fallback 0)
    } | ForEach-Object {
        $orderIdB64 = [string] (Get-FieldValue -Record $_ -Name 'orderIdB64' -Fallback '')
        $scenario = $scenarioByOrder[$orderIdB64]
        [pscustomobject]@{
            runId = $RunId
            groupCode = if ($null -ne $scenario) { $scenario.groupCode } else { 'UNMAPPED' }
            orderIdB64 = $orderIdB64
            traceId = Get-FieldValue -Record $_ -Name 'traceId' -Fallback 'unavailable'
            messageId = Get-FieldValue -Record $_ -Name 'messageId' -Fallback 'unavailable'
            operation = Get-FieldValue -Record $_ -Name 'operation' -Fallback 'unavailable'
            flow = Get-FieldValue -Record $_ -Name 'flow' -Fallback 'none'
            stageIndex = Get-FieldValue -Record $_ -Name 'stageIndex' -Fallback ''
            deliveryCount = Get-FieldValue -Record $_ -Name 'deliveryCount' -Fallback '0'
            fromStatus = Get-FieldValue -Record $_ -Name 'fromStatus' -Fallback 'unavailable'
            toStatus = Get-FieldValue -Record $_ -Name 'toStatus' -Fallback 'unavailable'
            transition = Get-FieldValue -Record $_ -Name 'transition' -Fallback 'none'
            nextStageIndex = Get-FieldValue -Record $_ -Name 'nextStageIndex' -Fallback ''
            totalMs = Format-Milliseconds (Get-FieldValue -Record $_ -Name 'totalMs')
            applicationMs = Format-Milliseconds (Get-FieldValue -Record $_ -Name 'applicationMs')
            redisOrderMs = Format-Milliseconds (Get-FieldValue -Record $_ -Name 'redisOrderMs')
            dbMs = Format-Milliseconds (Get-FieldValue -Record $_ -Name 'dbMs')
            markerMs = Format-Milliseconds (Get-FieldValue -Record $_ -Name 'markerMs')
            barQueryMs = Format-Milliseconds (Get-FieldValue -Record $_ -Name 'barQueryMs')
            barCloseMs = Format-Milliseconds (Get-FieldValue -Record $_ -Name 'barCloseMs')
            rabbitPublishConfirmMs = Format-Milliseconds (
                Get-FieldValue -Record $_ -Name 'rabbitPublishConfirmMs')
            ackMs = Format-Milliseconds (Get-FieldValue -Record $_ -Name 'ackMs')
            queueAgeMs = Format-Milliseconds (Get-FieldValue -Record $_ -Name 'queueAgeMs')
            scheduledDelayMs = Format-Milliseconds (
                Get-FieldValue -Record $_ -Name 'scheduledDelayMs')
            deliveryOverdueMs = Format-Milliseconds (
                Get-FieldValue -Record $_ -Name 'deliveryOverdueMs')
            outcome = Get-FieldValue -Record $_ -Name 'outcome' -Fallback 'FAILED'
            errorClass = Get-FieldValue -Record $_ -Name 'errorClass' -Fallback 'none'
        }
    })
$details | Export-Csv -LiteralPath $detailsPath -NoTypeInformation -Encoding UTF8

$matrix = [System.Collections.Generic.List[psobject]]::new()
foreach ($scenario in @($scenarioByOrder.Values | Sort-Object groupCode, orderIdB64)) {
    $orderRecords = @($records | Where-Object {
        (Get-FieldValue -Record $_ -Name 'orderIdB64' -Fallback '') -eq $scenario.orderIdB64
    } | Sort-Object {
        [int64] (Get-FieldValue -Record $_ -Name 'completedAtEpochMs' -Fallback 0)
    })
    $row = [ordered]@{
        runId = $RunId
        groupCode = $scenario.groupCode
        userId = $scenario.userId
        orderIdB64 = $scenario.orderIdB64
    }
    foreach ($definition in $stageDefinitions) {
        $stageRecords = @(Get-StageRecords -Records $orderRecords -Definition $definition)
        $lastRecord = @($stageRecords | Select-Object -Last 1)
        $row[($definition.key + '_MS')] = if ($lastRecord.Count -gt 0) {
            Format-Milliseconds (Get-FieldValue -Record $lastRecord[0] -Name 'totalMs')
        } else {
            ''
        }
        $row[($definition.key + '_ATTEMPTS')] = $stageRecords.Count
        $row[($definition.key + '_OUTCOME')] = if ($lastRecord.Count -gt 0) {
            Get-FieldValue -Record $lastRecord[0] -Name 'outcome' -Fallback 'FAILED'
        } else {
            'NOT_EXECUTED'
        }
    }
    $lastOrderRecord = @($orderRecords | Select-Object -Last 1)
    $row['finalStatus'] = if ($lastOrderRecord.Count -gt 0) {
        Get-FieldValue -Record $lastOrderRecord[0] -Name 'toStatus' -Fallback (
            Get-FieldValue -Record $lastOrderRecord[0] -Name 'currentStatus' -Fallback 'unavailable')
    } else {
        'NOT_EXECUTED'
    }
    $matrix.Add([pscustomobject] $row)
}
$matrix | Export-Csv -LiteralPath $matrixPath -NoTypeInformation -Encoding UTF8

$stageSummary = [System.Collections.Generic.List[psobject]]::new()
foreach ($definition in $stageDefinitions) {
    $stageRecords = @(Get-StageRecords -Records @($records) -Definition $definition)
    $stageSummary.Add((New-StageSummaryRecord -Definition $definition -Records $stageRecords))
}
$stageSummary | Export-Csv -LiteralPath $summaryPath -NoTypeInformation -Encoding UTF8

$slowest = @($records |
    Sort-Object {
        [double] (Get-FieldValue -Record $_ -Name 'totalMs' -Fallback 0)
    } -Descending |
    Select-Object -First $TopSlowCount |
    ForEach-Object {
        [pscustomobject]@{
            groupCode = if ($scenarioByOrder.ContainsKey(
                    [string] (Get-FieldValue -Record $_ -Name 'orderIdB64' -Fallback ''))) {
                $scenarioByOrder[
                    [string] (Get-FieldValue -Record $_ -Name 'orderIdB64' -Fallback '')
                ].groupCode
            } else {
                'UNMAPPED'
            }
            orderIdB64 = Get-FieldValue -Record $_ -Name 'orderIdB64' -Fallback 'unavailable'
            traceId = Get-FieldValue -Record $_ -Name 'traceId' -Fallback 'unavailable'
            messageId = Get-FieldValue -Record $_ -Name 'messageId' -Fallback 'unavailable'
            flow = Get-FieldValue -Record $_ -Name 'flow' -Fallback 'none'
            stageIndex = Get-FieldValue -Record $_ -Name 'stageIndex' -Fallback ''
            deliveryCount = Get-FieldValue -Record $_ -Name 'deliveryCount' -Fallback '0'
            outcome = Get-FieldValue -Record $_ -Name 'outcome' -Fallback 'FAILED'
            totalMs = [double] (Get-FieldValue -Record $_ -Name 'totalMs' -Fallback 0)
        }
    })

$jsonStageSummary = @($stageDefinitions | ForEach-Object {
    $definition = $_
    $stageRecords = @(Get-StageRecords -Records @($records) -Definition $definition)
    [pscustomobject]@{
        stage = $definition.label
        flow = $definition.flow
        stageIndex = $definition.stageIndex
        attemptCount = $stageRecords.Count
        uniqueOrderCount = @($stageRecords |
            ForEach-Object { Get-FieldValue -Record $_ -Name 'orderIdB64' -Fallback '' } |
            Sort-Object -Unique).Count
        durations = New-DurationSummary -Records $stageRecords
        outcomes = [pscustomobject]@{
            acked = @($stageRecords | Where-Object {
                (Get-FieldValue -Record $_ -Name 'outcome' -Fallback '') -eq 'ACKED'
            }).Count
            nacked = @($stageRecords | Where-Object {
                (Get-FieldValue -Record $_ -Name 'outcome' -Fallback '') -eq 'NACKED'
            }).Count
            failed = @($stageRecords | Where-Object {
                (Get-FieldValue -Record $_ -Name 'outcome' -Fallback '') -notin @('ACKED', 'NACKED')
            }).Count
        }
    }
})

$report = [pscustomobject]@{
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    runId = $RunId
    sourceFiles = @($resolvedPaths)
    scenarioOrderCount = $scenarioByOrder.Count
    sampleCount = $records.Count
    throughput = New-ThroughputSummary -Records @($records)
    stages = $jsonStageSummary
    slowest = $slowest
    artifacts = [pscustomobject]@{
        detailsCsv = $detailsPath
        orderMatrixCsv = $matrixPath
        stageSummaryCsv = $summaryPath
        markdownReport = $markdownPath
    }
}
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$markdownLines = [System.Collections.Generic.List[string]]::new()
$markdownLines.Add('# 会员支付状态机毫秒耗时测试报告')
$markdownLines.Add('')
$markdownLines.Add(('- Run ID：{0}' -f $RunId))
$markdownLines.Add(('- 生成时间（UTC）：{0}' -f $report.generatedAtUtc))
$markdownLines.Add(('- 场景订单数：{0}' -f $scenarioByOrder.Count))
$markdownLines.Add(('- 状态机实际消费次数：{0}' -f $records.Count))
$markdownLines.Add('')
$markdownLines.Add('> 本轮是全量详细日志运行；JMeter 吞吐和尾延迟包含日志队列、格式化与磁盘写入造成的整体资源开销，不能作为关闭明细日志后的纯容量上限。')
$markdownLines.Add('')
$markdownLines.Add('## 14 个状态机阶段')
$markdownLines.Add('')
$markdownLines.Add('| 阶段 | 尝试次数 | 唯一订单 | 平均值 | P50 | P95 | P99 | 最大值 | ACK | NACK | FAILED |')
$markdownLines.Add('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
foreach ($summaryRow in $stageSummary) {
    $average = if ([string]::IsNullOrWhiteSpace($summaryRow.averageMs)) { '—' } else { $summaryRow.averageMs + ' ms' }
    $p50 = if ([string]::IsNullOrWhiteSpace($summaryRow.p50Ms)) { '—' } else { $summaryRow.p50Ms + ' ms' }
    $p95 = if ([string]::IsNullOrWhiteSpace($summaryRow.p95Ms)) { '—' } else { $summaryRow.p95Ms + ' ms' }
    $p99 = if ([string]::IsNullOrWhiteSpace($summaryRow.p99Ms)) { '—' } else { $summaryRow.p99Ms + ' ms' }
    $maximum = if ([string]::IsNullOrWhiteSpace($summaryRow.maximumMs)) { '—' } else { $summaryRow.maximumMs + ' ms' }
    $markdownLines.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} |' -f
        $summaryRow.stage,
        $summaryRow.attemptCount,
        $summaryRow.uniqueOrderCount,
        $average,
        $p50,
        $p95,
        $p99,
        $maximum,
        $summaryRow.ackCount,
        $summaryRow.nackCount,
        $summaryRow.failedCount))
}
$markdownLines.Add('')
$markdownLines.Add('## 关联文件')
$markdownLines.Add('')
$markdownLines.Add('- 每次真实消费：membership-payment-order-stage-details.csv')
$markdownLines.Add('- 每个订单的 PENDING 0～8 / CLOSING 0～4 矩阵：membership-payment-order-stage-matrix.csv')
$markdownLines.Add('- 可复算的 14 阶段聚合：membership-payment-stage-summary.csv')
$markdownLines.Add('- 机器可读报告与最慢记录：membership-payment-timing-report.json')
$markdownLines | Set-Content -LiteralPath $markdownPath -Encoding UTF8

Write-Output ('Membership payment timing artifacts written directly to: {0}' -f
    $fullOutputDirectory)
