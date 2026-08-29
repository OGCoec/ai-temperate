[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $RunRoot,
    [ValidateRange(1, 10000)]
    [int] $ExpectedRowsPerSegment = 5000,
    [string] $PreviousRunRoot = '',
    [Parameter(Mandatory = $true)]
    [string] $FocusedTimingSummaryCsvPath,
    [string[]] $ApplicationLogPath = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedRoot = (Resolve-Path -LiteralPath $RunRoot).Path
$modulePath = Join-Path $PSScriptRoot 'MembershipSchedulerIndexHikariEvidence.psm1'
Import-Module $modulePath -Force
$groups = @('E-P1','E-PR','E-A1','E-AR','H-P1','H-PR','H-A1','H-AR')
$latencyDetailsPath = Join-Path $resolvedRoot 'callback-resolution-latency.csv'
$latencySummaryPath = Join-Path $resolvedRoot 'callback-resolution-latency-summary.json'
$specialVerdictPath = Join-Path $resolvedRoot 'scheduler-index-hikari-verdict.json'
$markdownPath = Join-Path $resolvedRoot 'scheduler-index-hikari-report.md'
$manifestPath = Join-Path $resolvedRoot 'run-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Formal run manifest is missing: $manifestPath"
}
$runManifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$manifestGroups = @($runManifest.groups)
if ($manifestGroups.Count -ne 0) {
    # 续跑只裁决本次实际执行的区段；已完成的前序区段由原 Run 证据单独保留。
    $groups = $manifestGroups
}
if ($null -eq $runManifest.connectionContract) {
    throw 'Formal run manifest does not contain a connection contract.'
}
$connectionContract = $runManifest.connectionContract
$expectedHikariMaximum = [int]$connectionContract.hikariMaximumPoolSize
$expectedHikariMinimumIdle = [int]$connectionContract.hikariMinimumIdle
$postgresMaxConnections = [int]$connectionContract.postgresMaxConnections

function Get-LatencyBucket([double] $Milliseconds) {
    if ($Milliseconds -lt 1000D) { return '<1s' }
    if ($Milliseconds -lt 5000D) { return '1s-5s' }
    if ($Milliseconds -lt 8000D) { return '5s-8s' }
    if ($Milliseconds -lt 10000D) { return '8s-10s' }
    return '>=10s'
}

function Read-LatencyEvidence(
        [string] $Root,
        [string[]] $ExpectedGroups,
        [int] $ExpectedRows,
        [bool] $RequireEveryGroup) {
    $details = [Collections.Generic.List[object]]::new()
    $summaries = [Collections.Generic.List[object]]::new()
    foreach ($group in $ExpectedGroups) {
        $path = Join-Path $Root "$group\server-time-verdict.csv"
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            if ($RequireEveryGroup) {
                throw "Server timestamp evidence is missing: $path"
            }
            continue
        }
        $rows = @(Import-Csv -LiteralPath $path)
        if ($RequireEveryGroup -and $rows.Count -ne $ExpectedRows) {
            throw "Server timestamp evidence row count is invalid: $group=$($rows.Count)"
        }
        $values = [Collections.Generic.List[double]]::new()
        foreach ($row in $rows) {
            if (-not [string]::IsNullOrWhiteSpace([string]$row.failure)) {
                throw "Server timestamp evidence contains a functional failure: $group"
            }
            $received = [datetimeoffset]::Parse([string]$row.received_at)
            $resolved = [datetimeoffset]::Parse([string]$row.callback_resolved_at)
            $milliseconds = ($resolved - $received).TotalMilliseconds
            if ($milliseconds -lt 0D) {
                throw "Callback resolution timestamp precedes receipt: $group"
            }
            $values.Add($milliseconds)
            $details.Add([pscustomobject][ordered]@{
                runId = [string]$row.run_id
                segment = $group
                orderId = [string]$row.order_id
                receivedAt = $received.ToString('O')
                callbackResolvedAt = $resolved.ToString('O')
                callbackResolutionMs = [Math]::Round($milliseconds, 3)
                bucket = Get-LatencyBucket $milliseconds
            })
        }
        $summaries.Add([pscustomobject][ordered]@{
            segment = $group
            statistics = New-MembershipCallbackLatencySummary -Values $values.ToArray()
        })
    }
    return [pscustomobject]@{
        details = @($details)
        summaries = @($summaries)
    }
}

$currentLatency = Read-LatencyEvidence `
    -Root $resolvedRoot `
    -ExpectedGroups $groups `
    -ExpectedRows $ExpectedRowsPerSegment `
    -RequireEveryGroup $true
$currentLatency.details |
    Export-Csv -LiteralPath $latencyDetailsPath -NoTypeInformation -Encoding UTF8
$overallLatency = New-MembershipCallbackLatencySummary -Values @(
    $currentLatency.details | ForEach-Object { [double]$_.callbackResolutionMs })
$previousSummaries = @()
if (-not [string]::IsNullOrWhiteSpace($PreviousRunRoot) -and
        (Test-Path -LiteralPath $PreviousRunRoot -PathType Container)) {
    $previous = Read-LatencyEvidence `
        -Root (Resolve-Path -LiteralPath $PreviousRunRoot).Path `
        -ExpectedGroups $groups `
        -ExpectedRows 0 `
        -RequireEveryGroup $false
    $previousSummaries = $previous.summaries
}
[ordered]@{
    generatedAt = [datetimeoffset]::UtcNow.ToString('O')
    targets = [ordered]@{
        atLeast10SecondsCount = 0
        maximumExclusiveMs = 8000
    }
    overall = $overallLatency
    segments = $currentLatency.summaries
    previousComparableSegments = $previousSummaries
} | ConvertTo-Json -Depth 12 |
    Set-Content -LiteralPath $latencySummaryPath -Encoding UTF8

$threadDumpPath = Join-Path $resolvedRoot 'membership-payment-thread-dump.txt'
$queuePath = Join-Path $resolvedRoot 'scheduler-queue-samples.csv'
$hikariPath = Join-Path $resolvedRoot 'hikari-runtime-samples.csv'
$redisWritePath = Join-Path $resolvedRoot 'redis-write-runtime-samples.csv'
$postgresPath = Join-Path $resolvedRoot 'postgres-connection-samples.csv'
$requiredRuntimePaths = @(
    $threadDumpPath,
    $queuePath,
    $hikariPath,
    $redisWritePath,
    $postgresPath)
$missingRuntime = @($requiredRuntimePaths | Where-Object {
    -not (Test-Path -LiteralPath $_ -PathType Leaf)
})
if ($missingRuntime.Count -gt 0) {
    throw "Runtime evidence is missing: $($missingRuntime -join ', ')"
}
$threadDump = Get-Content -Raw -LiteralPath $threadDumpPath
$queueRows = @(Import-Csv -LiteralPath $queuePath)
$hikariRows = @(Import-Csv -LiteralPath $hikariPath)
$redisWriteRows = @(Import-Csv -LiteralPath $redisWritePath)
$postgresRows = @(Import-Csv -LiteralPath $postgresPath)
$requiredRedisWriteColumns = @(
    'configuredBatchSize', 'configuredLaneCount', 'maximumInflight',
    'inflight', 'availablePermits',
    'lane0QueueDepth', 'lane1QueueDepth', 'lane2QueueDepth',
    'lane3QueueDepth', 'lane4QueueDepth', 'lane5QueueDepth',
    'lane0FullRestoreQueueDepth', 'lane1FullRestoreQueueDepth',
    'lane2FullRestoreQueueDepth', 'lane3FullRestoreQueueDepth',
    'lane4FullRestoreQueueDepth', 'lane5FullRestoreQueueDepth',
    'lane0PaymentAttemptPatchQueueDepth', 'lane1PaymentAttemptPatchQueueDepth',
    'lane2PaymentAttemptPatchQueueDepth', 'lane3PaymentAttemptPatchQueueDepth',
    'lane4PaymentAttemptPatchQueueDepth', 'lane5PaymentAttemptPatchQueueDepth')
if ($redisWriteRows.Count -gt 0) {
    $missingRedisWriteColumns = @($requiredRedisWriteColumns | Where-Object {
        $redisWriteRows[0].PSObject.Properties.Name -notcontains $_
    })
    if ($missingRedisWriteColumns.Count -gt 0) {
        throw "Redis write runtime evidence is missing six-lane columns: $($missingRedisWriteColumns -join ', ')"
    }
}
if ($redisWriteRows.Count -eq 0 -or @($redisWriteRows | Where-Object {
            [int]$_.configuredBatchSize -ne 64 -or
            [int]$_.configuredLaneCount -ne 6 -or
            [int]$_.maximumInflight -ne 384 -or
            [int]$_.inflight -lt 0 -or
            [int]$_.inflight -gt 384 -or
            [int]$_.availablePermits -lt 0 -or
            [int]$_.availablePermits -gt 384 -or
            [int]$_.inflight + [int]$_.availablePermits -ne 384
        }).Count -gt 0) {
    throw 'Redis write runtime evidence violates the fixed 64x6/384 contract.'
}
foreach ($row in $redisWriteRows) {
    for ($lane = 0; $lane -lt 6; $lane += 1) {
        $total = [int]$row."lane${lane}QueueDepth"
        $fullRestore = [int]$row."lane${lane}FullRestoreQueueDepth"
        $paymentPatch = [int]$row."lane${lane}PaymentAttemptPatchQueueDepth"
        if ($total -ne $fullRestore + $paymentPatch) {
            throw "Redis write runtime lane $lane violates its classified queue-depth invariant."
        }
    }
}
$redisWriteContract = [ordered]@{
    pipelineBatchSize = 64
    laneCount = 6
    maximumInflight = 384
    maximumObservedInflight = [int](
        $redisWriteRows | Measure-Object -Property inflight -Maximum).Maximum
}
$schedulerVerdict = Get-MembershipSchedulerSpecialVerdict `
    -ThreadDumpText $threadDump `
    -QueueRows $queueRows `
    -RuntimeRows $hikariRows `
    -LatencySummary $overallLatency

if (-not (Test-Path -LiteralPath $FocusedTimingSummaryCsvPath -PathType Leaf)) {
    throw "Focused timing summary is missing: $FocusedTimingSummaryCsvPath"
}
$timingRows = @(Import-Csv -LiteralPath $FocusedTimingSummaryCsvPath)
if ($timingRows.Count -ne 2) {
    throw 'Focused timing summary must contain ORDER_CREATE and PAYMENT_ATTEMPT.'
}
$databaseP99Ms = [double](
    $timingRows | Measure-Object -Property dbP99Ms -Maximum).Maximum
$connectionErrorFound = $false
foreach ($pathExpression in $ApplicationLogPath) {
    foreach ($path in @(Get-Item -Path $pathExpression -ErrorAction SilentlyContinue |
            Where-Object { -not $_.PSIsContainer })) {
        if (Select-String -LiteralPath $path.FullName -Quiet -Pattern @(
                'Connection is not available',
                'too many clients',
                'remaining connection slots')) {
            $connectionErrorFound = $true
        }
    }
}
$samplerFailurePath = Join-Path $resolvedRoot 'evidence-sampler-failure.json'
$hikariArguments = @{
    Rows = $hikariRows
    PostgresRows = $postgresRows
    DatabaseP99Ms = $databaseP99Ms
    ExpectedMaximumPoolSize = $expectedHikariMaximum
    ExpectedMinimumIdle = $expectedHikariMinimumIdle
    PostgresMaxConnections = $postgresMaxConnections
}
if (Test-Path -LiteralPath $samplerFailurePath) {
    $hikariArguments.SamplerFailure = $true
}
if ($connectionErrorFound) {
    $hikariArguments.ConnectionErrorFound = $true
}
$hikariVerdict = Get-MembershipHikariSpecialVerdict @hikariArguments

$explainPath = Join-Path $resolvedRoot 'latest-paid-order-explain.json'
$indexBeforePath = Join-Path $resolvedRoot 'latest-paid-index-before.txt'
$indexAfterPath = Join-Path $resolvedRoot 'latest-paid-index-after.txt'
$upgradeProbePath = Join-Path $resolvedRoot 'latest-paid-upgrade-probe.json'
$explainVerdict = Test-MembershipLatestPaidExplain -Path $explainPath
$beforeScan = Get-MembershipIndexScanCount -Path $indexBeforePath
$afterScan = Get-MembershipIndexScanCount -Path $indexAfterPath
$upgradeProbe = if (Test-Path -LiteralPath $upgradeProbePath -PathType Leaf) {
    Get-Content -Raw -LiteralPath $upgradeProbePath | ConvertFrom-Json
} else {
    $null
}
$indexReasons = [Collections.Generic.List[string]]::new()
$indexEvidenceComplete = $null -ne $beforeScan -and $null -ne $afterScan -and
    $null -ne $upgradeProbe -and
    (ConvertTo-MembershipEvidenceBoolean $upgradeProbe.businessQueryExecuted)
if (-not $indexEvidenceComplete) {
    $indexVerdictValue = '证据不足'
    $indexReasons.Add('升级业务样本或索引统计不完整')
} elseif ($explainVerdict.verdict -ne 'PASS' -or
        ([long]$afterScan - [long]$beforeScan) -lt 2L -or
        [string]$upgradeProbe.verdict -ne 'PASS') {
    $indexVerdictValue = 'FAIL'
    if ($explainVerdict.verdict -ne 'PASS') {
        $indexReasons.Add('执行计划未命中指定索引或包含Sort/Seq Scan')
    }
    if (([long]$afterScan - [long]$beforeScan) -lt 2L) {
        $indexReasons.Add('idx_scan增量小于2')
    }
    if ([string]$upgradeProbe.verdict -ne 'PASS') {
        $indexReasons.Add('真实升级业务探针失败')
    }
} else {
    $indexVerdictValue = 'PASS'
}
$indexVerdict = [pscustomobject][ordered]@{
    verdict = $indexVerdictValue
    reasons = @($indexReasons)
    explain = $explainVerdict
    beforeIdxScan = $beforeScan
    afterIdxScan = $afterScan
    idxScanDelta = if ($null -ne $beforeScan -and $null -ne $afterScan) {
        [long]$afterScan - [long]$beforeScan
    } else {
        $null
    }
    businessProbeExecuted = $null -ne $upgradeProbe -and
        (ConvertTo-MembershipEvidenceBoolean $upgradeProbe.businessQueryExecuted)
}

$suiteVerdictPath = Join-Path $resolvedRoot 'verdict.json'
$suiteVerdict = if (Test-Path -LiteralPath $suiteVerdictPath -PathType Leaf) {
    Get-Content -Raw -LiteralPath $suiteVerdictPath | ConvertFrom-Json
} else {
    $null
}
$invalidEvidence = Test-Path -LiteralPath $samplerFailurePath
if ($null -eq $suiteVerdict) {
    $conclusion = '测试无效：配置、源码指纹或环境不符合合同。'
} elseif ([string]$suiteVerdict.verdict -ne 'PASS') {
    $message = if ($suiteVerdict.PSObject.Properties.Name -contains 'message') {
        [string]$suiteVerdict.message
    } else {
        ''
    }
    $conclusion = if ($message -match
            'fingerprint|配置|environment|环境|listener|source') {
        '测试无效：配置、源码指纹或环境不符合合同。'
    } else {
        '功能 FAIL，性能数据仅供诊断。'
    }
} elseif ($invalidEvidence) {
    $conclusion = '测试无效：配置、源码指纹或环境不符合合同。'
} else {
    $suitePerformancePassed =
        ConvertTo-MembershipEvidenceBoolean $suiteVerdict.performancePassed
    $allPerformancePassed = $suitePerformancePassed -and
        $schedulerVerdict.verdict -eq 'PASS' -and
        $indexVerdict.verdict -eq 'PASS' -and
        $hikariVerdict.verdict -eq '可接受'
    $conclusion = if ($allPerformancePassed) {
        '功能与性能均 PASS。'
    } else {
        '功能 PASS，但性能目标未达到。'
    }
}

$report = [ordered]@{
    generatedAt = [datetimeoffset]::UtcNow.ToString('O')
    conclusion = $conclusion
    callbackLatency = $overallLatency
    scheduler = $schedulerVerdict
    latestPaidIndex = $indexVerdict
    hikari = $hikariVerdict
    redisWrite = $redisWriteContract
    connectionContract = $connectionContract
    suiteVerdict = $suiteVerdict
    artifacts = [ordered]@{
        callbackLatencyCsv = $latencyDetailsPath
        callbackLatencySummaryJson = $latencySummaryPath
        markdown = $markdownPath
    }
}
$report | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath $specialVerdictPath -Encoding UTF8

$markdown = [Collections.Generic.List[string]]::new()
$markdown.Add("# Membership Payment Scheduler / Index / Hikari $expectedHikariMaximum 正式复测报告")
$markdown.Add('')
$markdown.Add("- 总体结论：$conclusion")
$markdown.Add("- 调度专项：$($schedulerVerdict.verdict)")
$markdown.Add("- 最近 PAID 索引专项：$($indexVerdict.verdict)")
$markdown.Add("- Hikari $expectedHikariMaximum：$($hikariVerdict.verdict)")
$markdown.Add("- PostgreSQL $postgresMaxConnections：连接峰值必须严格低于硬上限")
$markdown.Add("- Redis Pipeline：$($redisWriteContract.pipelineBatchSize)")
$markdown.Add("- Redis lane：$($redisWriteContract.laneCount)")
$markdown.Add("- Redis 总逻辑在途：$($redisWriteContract.maximumInflight)")
$markdown.Add("- Callback P50/P95/P99/max：$($overallLatency.p50Ms) / $($overallLatency.p95Ms) / $($overallLatency.p99Ms) / $($overallLatency.maximumMs) ms")
$markdown.Add("- Callback >=10s：$($overallLatency.atLeast10SecondsCount)")
$markdown.Add("- PostgreSQL 会话峰值：$($hikariVerdict.postgresPeakConnections)")
$markdown.Add("- idx_scan 增量：$($indexVerdict.idxScanDelta)")
$markdown | Set-Content -LiteralPath $markdownPath -Encoding UTF8

Write-Output "Membership scheduler/index/Hikari report written to: $resolvedRoot"
