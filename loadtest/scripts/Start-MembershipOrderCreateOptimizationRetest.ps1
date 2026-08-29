[CmdletBinding()]
param(
    [string] $MasterRunId = '',
    [string[]] $InitialPreviousScenarioOrdersCsvPath = @(),
    [string] $InitialComparableRunRoot = '',
    [ValidateSet(384)]
    [int] $PostgresMaxConnections = 384,
    [ValidateSet(256)]
    [int] $HikariMaximumPoolSize = 256,
    [ValidateSet(8)]
    [int] $HikariMinimumIdle = 8,
    [ValidateRange(0, 64)]
    [int] $MaximumNavicatConnections = 8,
    [ValidateSet(64)]
    [int] $RedisWriteBatchSize = 64,
    [ValidateSet(6)]
    [int] $RedisWriteLaneCount = 6,
    [ValidateSet(384)]
    [int] $RedisWriteMaximumInflight = 384
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 单个历史场景路径也必须保持集合语义，确保写入子进程配置时始终生成 JSON 数组。
$InitialPreviousScenarioOrdersCsvPath = @($InitialPreviousScenarioOrdersCsvPath)

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$fixedJarSha256 = 'A91B1EEED2085748C5B615A290AFC913DE2623B7DE17EA3995690C880C9EBD45'
$goldenBaselineRunId = 'membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048'
$goldenBaselineEvidenceRoot = Join-Path $repositoryRoot `
    "loadtest-output\soak\$goldenBaselineRunId\millisecond-boundary"
if ([string]::IsNullOrWhiteSpace($MasterRunId)) {
    $MasterRunId = 'membership-order-create-optimization-' +
        (Get-Date -Format 'yyyyMMdd-HHmmss')
}
if ($MasterRunId -notmatch '^[A-Za-z0-9_-]{1,96}$') {
    throw 'MasterRunId may contain only letters, digits, underscore and hyphen.'
}
$masterRoot = Join-Path $repositoryRoot "loadtest-output\soak\$MasterRunId"
if (Test-Path -LiteralPath $masterRoot) {
    throw "The optimization retest root already exists: $masterRoot"
}
New-Item -ItemType Directory -Path $masterRoot | Out-Null
$runStatePath = Join-Path $masterRoot 'run-state.json'
$heartbeatPath = Join-Path $masterRoot 'heartbeat.json'
$runLedgerPath = Join-Path $masterRoot 'run-ledger.json'
$finalVerdictPath = Join-Path $masterRoot 'final-verdict.md'
$sourceFingerprintPath = Join-Path $masterRoot 'source-fingerprint.json'
$childWrapperScript = Join-Path $PSScriptRoot 'Invoke-MembershipOptimizationRunChild.ps1'
$runs = [Collections.Generic.List[object]]::new()
$currentRunId = $null
$currentChildPid = $null
$currentStage = 'INITIALIZING'
$previousScenarioPaths = @($InitialPreviousScenarioOrdersCsvPath)
$previousComparableRoot = $InitialComparableRunRoot
$ownedApplicationPid = 0
$ownedApplicationDescriptorPath = ''
$postgresStabilityGatePath = ''
$allGroupCodes = @('E-P1','E-PR','E-A1','E-AR','H-P1','H-PR','H-A1','H-AR')

function Write-AtomicJson([string] $Path, [object] $Value) {
    $temporaryPath = "$Path.$PID.$([guid]::NewGuid().ToString('N')).partial"
    try {
        # 空数组不会向 PowerShell 管道发送对象，因此必须用 InputObject 确保初始 ledger 也落成合法的 []。
        $jsonBytes = [Text.UTF8Encoding]::new($false).GetBytes(
            (ConvertTo-Json -InputObject $Value -Depth 12))
        $stream = [IO.FileStream]::new(
            $temporaryPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
            [IO.FileShare]::Read, 4096, [IO.FileOptions]::WriteThrough)
        try {
            $stream.Write($jsonBytes, 0, $jsonBytes.Length)
            $stream.Flush($true)
        } finally {
            $stream.Dispose()
        }

        $retryClock = [Diagnostics.Stopwatch]::StartNew()
        $retryDelays = @(25, 50, 100, 200, 250)
        $retryAttempt = 0
        while ($true) {
            try {
                [IO.File]::Move($temporaryPath, $Path, $true)
                return
            } catch {
                # PowerShell 会把 File.Move 的原始异常包装起来，必须遍历异常链识别 Windows 共享冲突。
                $transientLockFailure = $false
                $currentException = $_.Exception
                while ($null -ne $currentException) {
                    $nativeError = $currentException.HResult -band 0xFFFF
                    if ($nativeError -in @(5, 32, 33)) {
                        $transientLockFailure = $true
                        break
                    }
                    $currentException = $currentException.InnerException
                }
                if (-not $transientLockFailure) {
                    throw
                }
                if ($retryClock.Elapsed.TotalSeconds -ge 10D) {
                    throw [IO.IOException]::new(
                        "TEST_INVALID_EVIDENCE_PUBLICATION: JSON destination remained locked for 10 seconds: $Path",
                        $_.Exception)
                }
                $delay = $retryDelays[[Math]::Min(
                    $retryAttempt, $retryDelays.Count - 1)]
                $remainingMilliseconds = [Math]::Max(
                    1, [int][Math]::Ceiling(
                        (10D - $retryClock.Elapsed.TotalSeconds) * 1000D))
                Start-Sleep -Milliseconds ([Math]::Min($delay, $remainingMilliseconds))
                $retryAttempt += 1
            }
        }
    } finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-JsonSnapshot([string] $Path) {
    $stream = [IO.File]::Open(
        $Path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        $reader = [IO.StreamReader]::new(
            $stream, [Text.UTF8Encoding]::new($false), $true, 4096, $true)
        try {
            $raw = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
    return ConvertFrom-Json -InputObject $raw
}

function Get-TextSha256([string] $Value) {
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
}

function Get-FrozenSource {
    $jarPath = Join-Path $repositoryRoot `
        'ai-temperate-web\target\ai-temperate-web-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "The packaged application JAR is missing: $jarPath"
    }
    Push-Location $repositoryRoot
    try {
        $paths = @(
            'ai-temperate-common','ai-temperate-model','ai-temperate-mapper',
            'ai-temperate-service','ai-temperate-web','loadtest','sql','docs','pom.xml')
        $status = (& git status --short -- @paths 2>$null | Out-String)
        $diff = (& git diff --binary HEAD -- @paths 2>$null | Out-String)
        $untracked = @(& git ls-files --others --exclude-standard -- @paths 2>$null |
            Where-Object { $_ } | Sort-Object)
        $untrackedFacts = foreach ($relative in $untracked) {
            $absolute = Join-Path $repositoryRoot $relative
            if (Test-Path -LiteralPath $absolute -PathType Leaf) {
                "$relative=$((Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash)"
            }
        }
        return [ordered]@{
            gitHead = (& git rev-parse HEAD 2>$null).Trim()
            gitStatusSha256 = Get-TextSha256 $status
            gitDiffSha256 = Get-TextSha256 $diff
            untrackedSourceSha256 = Get-TextSha256 ($untrackedFacts -join "`n")
            jarSha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } finally {
        Pop-Location
    }
}

function Assert-FrozenSource {
    $actual = Get-FrozenSource
    if (($actual | ConvertTo-Json -Compress) -ne
            ($frozenSource | ConvertTo-Json -Compress)) {
        throw 'Source, worktree or packaged JAR drifted after the master run was frozen.'
    }
}

function Save-MasterState([string] $Status) {
    $childProgress = Get-CurrentChildProgress
    $publishedApplicationPid = $childProgress.applicationPid
    $publishedSamplerPid = $childProgress.samplerPid
    $publishedSuitePid = $childProgress.suitePid
    if ($Status -ne 'RUNNING') {
        # 终止态保留最后阶段证据，但不得把子心跳中的历史 PID 误报为存活进程。
        $publishedApplicationPid = $null
        $publishedSamplerPid = $null
        $publishedSuitePid = $null
    }
    Write-AtomicJson -Path $runStatePath -Value ([ordered]@{
        masterRunId = $MasterRunId
        status = $Status
        stage = $currentStage
        currentChildRunId = $currentRunId
        currentChildPid = $currentChildPid
        orchestratorPid = $PID
        applicationPid = $publishedApplicationPid
        samplerPid = $publishedSamplerPid
        suitePid = $publishedSuitePid
        currentGroupCode = $childProgress.currentGroupCode
        warmupAttempt = $childProgress.warmupAttempt
        childPhase = $childProgress.phase
        childSuiteState = $childProgress.suiteState
        completedRunCount = $runs.Count
        updatedAt = [datetimeoffset]::UtcNow.ToString('O')
    })
    Write-AtomicJson -Path $heartbeatPath -Value ([ordered]@{
        masterRunId = $MasterRunId
        stage = $currentStage
        currentChildRunId = $currentRunId
        currentChildPid = $currentChildPid
        orchestratorPid = $PID
        applicationPid = $publishedApplicationPid
        samplerPid = $publishedSamplerPid
        suitePid = $publishedSuitePid
        currentGroupCode = $childProgress.currentGroupCode
        warmupAttempt = $childProgress.warmupAttempt
        childPhase = $childProgress.phase
        childSuiteState = $childProgress.suiteState
        sampledAt = [datetimeoffset]::UtcNow.ToString('O')
    })
    Write-AtomicJson -Path $runLedgerPath -Value @($runs)
}

function Get-ChildRoot([string] $RunId) {
    return Join-Path $repositoryRoot "loadtest-output\soak\$RunId\millisecond-boundary"
}

function Get-WorstComparisonMetric([object[]] $Comparisons, [string] $MetricName) {
    $facts = @($Comparisons | ForEach-Object {
        [pscustomobject]@{
            groupCode = [string]$_.groupCode
            value = [double]$_.current.focusedLatency.$MetricName
        }
    })
    $worst = @($facts | Sort-Object value -Descending | Select-Object -First 1)
    if ($worst.Count -ne 1) {
        throw "Unable to summarize focused latency metric: $MetricName"
    }
    return [ordered]@{
        valueMs = $worst[0].value
        groupCode = $worst[0].groupCode
    }
}

function Get-CurrentChildProgress {
    if ([string]::IsNullOrWhiteSpace([string]$currentRunId)) {
        return [pscustomobject]@{
            applicationPid=$null; samplerPid=$null; suitePid=$null;
            currentGroupCode=$null; warmupAttempt=$null; phase=$null; suiteState=$null
        }
    }
    $childHeartbeatPath = Join-Path (Get-ChildRoot $currentRunId) 'heartbeat.json'
    if (-not (Test-Path -LiteralPath $childHeartbeatPath -PathType Leaf)) {
        return [pscustomobject]@{
            applicationPid=if ($ownedApplicationPid -gt 0) { $ownedApplicationPid } else { $null };
            samplerPid=$null; suitePid=$null; currentGroupCode=$null;
            warmupAttempt=$null; phase='STARTING'; suiteState=$null
        }
    }
    try {
        $heartbeat = Read-JsonSnapshot -Path $childHeartbeatPath
        return [pscustomobject]@{
            applicationPid=$heartbeat.applicationPid
            samplerPid=$heartbeat.samplerPid
            suitePid=$heartbeat.suitePid
            currentGroupCode=$heartbeat.currentGroupCode
            warmupAttempt=$heartbeat.warmupAttempt
            phase=$heartbeat.phase
            suiteState=$heartbeat.suiteState
        }
    } catch {
        return [pscustomobject]@{
            applicationPid=$null; samplerPid=$null; suitePid=$null;
            currentGroupCode=$null; warmupAttempt=$null; phase='HEARTBEAT_UNREADABLE'; suiteState=$null
        }
    }
}

function Invoke-FixedRun(
        [string] $Stage,
        [string] $Scale,
        [bool] $KeepApplicationRunningAfterSuite,
        [bool] $ReuseExistingApplication,
        [bool] $DirectConcurrencyCanary,
        [int] $ExpectedFormalSegmentCount) {
    if ($ExpectedFormalSegmentCount -notin @(1, 8) -or
            ($DirectConcurrencyCanary -and $ExpectedFormalSegmentCount -ne 1) -or
            (-not $DirectConcurrencyCanary -and $ExpectedFormalSegmentCount -ne 8)) {
        throw 'Formal segment count must be 1 for Canary and 8 for 40K/80K.'
    }
    $script:currentStage = $Stage
    $safeStage = $Stage.ToLowerInvariant() -replace '[^a-z0-9]+', '-'
    $script:currentRunId = "$MasterRunId-$safeStage"
    Assert-FrozenSource
    Save-MasterState -Status 'RUNNING'
    $childConfigurationPath = Join-Path $masterRoot "$currentRunId.configuration.json"
    Write-AtomicJson -Path $childConfigurationPath -Value ([ordered]@{
        masterRunId = $MasterRunId
        runId = $currentRunId
        postgresMaxConnections = $PostgresMaxConnections
        hikariMaximumPoolSize = $HikariMaximumPoolSize
        hikariMinimumIdle = $HikariMinimumIdle
        maximumNavicatConnections = $MaximumNavicatConnections
        runScale = $Scale
        redisWriteBatchSize = $RedisWriteBatchSize
        redisWriteLaneCount = $RedisWriteLaneCount
        redisWriteMaximumInflight = $RedisWriteMaximumInflight
        directConcurrencyCanary = $DirectConcurrencyCanary
        expectedFormalSegmentCount = $ExpectedFormalSegmentCount
        goldenBaselineRunId = $goldenBaselineRunId
        goldenBaselineEvidenceRoot = $goldenBaselineEvidenceRoot
        fixedJarSha256 = $fixedJarSha256
        previousScenarioOrdersCsvPath = @($previousScenarioPaths)
        previousComparableRunRoot = $previousComparableRoot
        keepApplicationRunningAfterSuite = $KeepApplicationRunningAfterSuite
        reuseExistingApplication = $ReuseExistingApplication
        existingApplicationPid = if ($ReuseExistingApplication) { $ownedApplicationPid } else { 0 }
        existingApplicationDescriptorPath = if ($ReuseExistingApplication) {
            $ownedApplicationDescriptorPath
        } else { '' }
        postgresStabilitySeconds = if ([string]::IsNullOrWhiteSpace($postgresStabilityGatePath)) {
            120
        } else { 0 }
        existingPostgresStabilityGatePath = $postgresStabilityGatePath
    })
    $childStdoutPath = Join-Path $masterRoot "$currentRunId.stdout.log"
    $childStderrPath = Join-Path $masterRoot "$currentRunId.stderr.log"
    $child = Start-Process -FilePath (Get-Command pwsh -ErrorAction Stop).Source `
        -ArgumentList @(
            '-NoProfile', '-File', $childWrapperScript,
            '-ConfigurationPath', $childConfigurationPath) `
        -WorkingDirectory $repositoryRoot `
        -RedirectStandardOutput $childStdoutPath `
        -RedirectStandardError $childStderrPath `
        -WindowStyle Hidden `
        -PassThru
    $script:currentChildPid = $child.Id
    Save-MasterState -Status 'RUNNING'
    while (-not $child.HasExited) {
        # 主编排心跳独立于 Codex 界面和子运行阶段，每两秒持续声明唯一子进程所有权。
        Save-MasterState -Status 'RUNNING'
        Start-Sleep -Seconds 2
        $child.Refresh()
    }
    $script:currentChildPid = $null
    Save-MasterState -Status 'RUNNING'
    $childRoot = Get-ChildRoot $currentRunId
    if ($child.ExitCode -ne 0) {
        $structuredChildFailurePath = Join-Path $childRoot 'orchestrator-failure.json'
        if (Test-Path -LiteralPath $structuredChildFailurePath -PathType Leaf) {
            try {
                $structuredChildFailure = Get-Content -Raw -LiteralPath `
                    $structuredChildFailurePath | ConvertFrom-Json
                $childFailureCode = if ($null -ne
                        $structuredChildFailure.PSObject.Properties['failureCode']) {
                    [string]$structuredChildFailure.failureCode
                } else { 'CHILD_ORCHESTRATION_FAILURE' }
                $childPrimaryMessage = if ($null -ne
                        $structuredChildFailure.PSObject.Properties['primaryMessage']) {
                    [string]$structuredChildFailure.primaryMessage
                } else { [string]$structuredChildFailure.message }
                Write-AtomicJson -Path (Join-Path $masterRoot 'child-failure.json') `
                    -Value $structuredChildFailure
                throw "${childFailureCode}: $childPrimaryMessage"
            } catch {
                if ($_.Exception.Message -match '^[A-Z][A-Z0-9_]+:') { throw }
            }
        }
        throw "CHILD_PROCESS_EXIT_FAILURE: child $currentRunId exited with code $($child.ExitCode); diagnostics: $childStderrPath"
    }
    $suiteVerdictPath = Join-Path $childRoot 'verdict.json'
    $qpsPath = Join-Path $childRoot 'order-create-segment-qps.csv'
    if (-not (Test-Path -LiteralPath $suiteVerdictPath -PathType Leaf) -or
            -not (Test-Path -LiteralPath $qpsPath -PathType Leaf)) {
        throw "Child run did not produce its functional and HTTP evidence: $currentRunId"
    }
    $suiteVerdict = Get-Content -Raw -LiteralPath $suiteVerdictPath | ConvertFrom-Json
    $qpsRows = @(Import-Csv -LiteralPath $qpsPath)
    $qpsPassed = $qpsRows.Count -eq $ExpectedFormalSegmentCount -and
        @($qpsRows | Where-Object verdict -ne 'PASS').Count -eq 0
    if ($suiteVerdict.verdict -ne 'PASS') {
        throw "Child run failed its functional Suite gate: $currentRunId"
    }
    $previousCleanupReceiptPath = Join-Path $childRoot 'previous-exact-reset-receipt.json'
    if ($Stage -in @('PERFORMANCE_40K','CAPACITY_80K')) {
        $expectedPreviousFormalCount = if ($Stage -eq 'PERFORMANCE_40K') { 5000 } else { 40000 }
        if (-not (Test-Path -LiteralPath $previousCleanupReceiptPath -PathType Leaf)) {
            throw "Previous formal data cleanup receipt is missing: $Stage"
        }
        $previousCleanupReceipt = Get-Content -Raw -LiteralPath $previousCleanupReceiptPath |
            ConvertFrom-Json
        if ([long]$previousCleanupReceipt.deletedOrderCount -ne $expectedPreviousFormalCount -or
                [long]$previousCleanupReceipt.deletedCallbackCount -ne $expectedPreviousFormalCount -or
                [long]$previousCleanupReceipt.resetQuotaCount -ne $expectedPreviousFormalCount -or
                [long]$previousCleanupReceipt.currentFixedFixtureOrderCount -ne 0L -or
                [long]$previousCleanupReceipt.currentFixedFixtureCallbackCount -ne 0L -or
                [long]$previousCleanupReceipt.retainedFormalOrderCount -ne 0L -or
                [long]$previousCleanupReceipt.retainedFormalCallbackCount -ne 0L) {
            throw "Previous formal data cleanup receipt is invalid: $Stage"
        }
    }
    $minimumQps = if ($qpsRows.Count -eq 0) { 0D } else {
        [double](($qpsRows | Measure-Object qps -Minimum).Minimum)
    }
    $goldenComparisonRows = @(
        Get-ChildItem -LiteralPath $childRoot -Filter 'golden-baseline-comparison.json' `
            -File -Recurse -ErrorAction Stop | Where-Object {
                $_.DirectoryName -notmatch '[\\/]warmup[\\/]'
            } | ForEach-Object {
                Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json
            })
    if ($goldenComparisonRows.Count -ne $ExpectedFormalSegmentCount) {
        throw "Child run did not produce one golden comparison per formal segment: expected=$ExpectedFormalSegmentCount actual=$($goldenComparisonRows.Count)"
    }
    $orderedQpsRows = @($qpsRows | Sort-Object {
        [Array]::IndexOf($allGroupCodes, [string]$_.segment)
    })
    $segmentSummaries = @($orderedQpsRows | ForEach-Object {
        $qpsRow = $_
        $comparison = @($goldenComparisonRows | Where-Object groupCode -eq $qpsRow.segment)
        if ($comparison.Count -ne 1) {
            throw "Formal segment does not have one golden comparison: $($qpsRow.segment)"
        }
        [pscustomobject][ordered]@{
            groupCode = [string]$qpsRow.segment
            wallClockSeconds = [double]$qpsRow.wallClockSeconds
            qps = [double]$qpsRow.qps
            effectiveCreateConcurrency = [double]$qpsRow.effectiveCreateConcurrency
            contractVerdict = [string]$qpsRow.verdict
            goldenReproduction = [string]$comparison[0].goldenReproduction
            frontHalfQps = [double]$comparison[0].current.frontHalfQps
            backHalfQps = [double]$comparison[0].current.backHalfQps
            focusedLatency = $comparison[0].current.focusedLatency
        }
    })
    $slowestSegment = @($segmentSummaries |
        Sort-Object wallClockSeconds -Descending | Select-Object -First 1)[0]
    $maximumWallClockSeconds = [double]$slowestSegment.wallClockSeconds
    $layerWorstP95P99 = [ordered]@{
        orderCreate = [ordered]@{
            p95 = Get-WorstComparisonMetric $goldenComparisonRows 'orderCreateP95Ms'
            p99 = Get-WorstComparisonMetric $goldenComparisonRows 'orderCreateP99Ms'
        }
        redisQueue = [ordered]@{
            p95 = Get-WorstComparisonMetric $goldenComparisonRows 'redisQueueP95Ms'
            p99 = Get-WorstComparisonMetric $goldenComparisonRows 'redisQueueP99Ms'
        }
        redisPipelineExecute = [ordered]@{
            p95 = Get-WorstComparisonMetric $goldenComparisonRows 'redisPipelineExecuteP95Ms'
            p99 = Get-WorstComparisonMetric $goldenComparisonRows 'redisPipelineExecuteP99Ms'
        }
        rabbitPublish = [ordered]@{
            p95 = Get-WorstComparisonMetric $goldenComparisonRows 'rabbitPublishP95Ms'
            p99 = Get-WorstComparisonMetric $goldenComparisonRows 'rabbitPublishP99Ms'
        }
        rabbitConfirm = [ordered]@{
            p95 = Get-WorstComparisonMetric $goldenComparisonRows 'rabbitConfirmP95Ms'
            p99 = Get-WorstComparisonMetric $goldenComparisonRows 'rabbitConfirmP99Ms'
        }
        databaseTransaction = [ordered]@{
            p95 = Get-WorstComparisonMetric $goldenComparisonRows 'dbTransactionP95Ms'
            p99 = Get-WorstComparisonMetric $goldenComparisonRows 'dbTransactionP99Ms'
        }
    }
    $eightSegmentContinuity = if ($ExpectedFormalSegmentCount -eq 8) {
        if ($qpsPassed) { '全部持续达标' } else { '部分区段性能回退' }
    } else { '单区段Canary' }
    $sustainabilitySummaryPath = if ($ExpectedFormalSegmentCount -eq 8) {
        Join-Path $childRoot 'eight-segment-sustainability-summary.json'
    } else {
        Join-Path $childRoot 'canary-segment-summary.json'
    }
    [ordered]@{
        runId = $currentRunId
        stage = $Stage
        expectedFormalSegmentCount = $ExpectedFormalSegmentCount
        actualFormalSegmentCount = $segmentSummaries.Count
        minimumSegmentQps = $minimumQps
        maximumSegmentWallClockSeconds = $maximumWallClockSeconds
        slowestSegment = $slowestSegment.groupCode
        layerWorstP95P99 = $layerWorstP95P99
        eightSegmentContinuity = $eightSegmentContinuity
        segments = $segmentSummaries
        generatedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $sustainabilitySummaryPath -Encoding UTF8
    $goldenTargetPassed = @($goldenComparisonRows | Where-Object {
        [string]$_.goldenReproduction -notin @('REPRODUCED','GOLDEN_CAPABILITY_TARGET_REACHED')
    }).Count -eq 0
    $contractVerdict = if ($qpsPassed) { 'PASS' } else { '性能WARN' }
    $goldenConclusion = if ($goldenTargetPassed) {
        if ($Scale -eq 'PERFORMANCE_40K') {
            '已复现（<5秒且>1,000 QPS）'
        } else {
            '已复现（10K区段<10秒且>1,000 QPS）'
        }
    } elseif ($qpsPassed) {
        '未复现但合同达标（≥900 QPS）'
    } else {
        '未复现且不达标'
    }
    $runRecord = [pscustomobject][ordered]@{
        runId = $currentRunId
        stage = $Stage
        scale = $Scale
        redisWriteBatchSize = $RedisWriteBatchSize
        redisWriteLaneCount = $RedisWriteLaneCount
        redisWriteMaximumInflight = $RedisWriteMaximumInflight
        functionalPassed = $true
        qpsPassed = $qpsPassed
        expectedFormalSegmentCount = $ExpectedFormalSegmentCount
        actualFormalSegmentCount = $qpsRows.Count
        contractVerdict = $contractVerdict
        goldenTargetPassed = $goldenTargetPassed
        goldenBaselineConclusion = $goldenConclusion
        performancePassed = [bool]$suiteVerdict.performancePassed
        minimumSegmentQps = $minimumQps
        maximumSegmentWallClockSeconds = $maximumWallClockSeconds
        layerWorstP95P99 = $layerWorstP95P99
        eightSegmentContinuity = $eightSegmentContinuity
        sustainabilitySummaryPath = $sustainabilitySummaryPath
        runRoot = $childRoot
        previousExactCleanupReceipt = if (Test-Path -LiteralPath $previousCleanupReceiptPath) {
            $previousCleanupReceiptPath
        } else { $null }
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    }
    if ([string]::IsNullOrWhiteSpace($postgresStabilityGatePath)) {
        $gatePath = Join-Path $childRoot 'postgres-stability-gate.json'
        $gate = Get-Content -Raw -LiteralPath $gatePath | ConvertFrom-Json
        if ([string]$gate.verdict -ne 'PASS' -or [int]$gate.observedSeconds -lt 120) {
            throw 'The Canary did not produce the required 120-second PostgreSQL stability gate.'
        }
        $script:postgresStabilityGatePath = $gatePath
    }
    if ($KeepApplicationRunningAfterSuite) {
        $descriptorPath = Join-Path $childRoot 'application-start.json'
        $descriptor = Get-Content -Raw -LiteralPath $descriptorPath | ConvertFrom-Json
        $script:ownedApplicationPid = [int]$descriptor.pid
        $script:ownedApplicationDescriptorPath = $descriptorPath
    }
    $runs.Add($runRecord)
    $script:previousScenarioPaths = @(Join-Path $childRoot 'scenario-orders-all.csv')
    $script:previousComparableRoot = $childRoot
    Save-MasterState -Status 'RUNNING'
    return $runRecord
}

function Stop-MasterOwnedApplication {
    if ($ownedApplicationPid -le 0) { return }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ownedApplicationPid" `
        -ErrorAction SilentlyContinue
    if ($null -ne $process -and
            $process.CommandLine -match 'ai-temperate-web-0\.0\.1-SNAPSHOT\.jar') {
        Stop-Process -Id $ownedApplicationPid -ErrorAction SilentlyContinue
        Wait-Process -Id $ownedApplicationPid -Timeout 20 -ErrorAction SilentlyContinue
    }
    $script:ownedApplicationPid = 0
}

$frozenSource = $null
try {
    $frozenSource = Get-FrozenSource
    if (-not [string]::Equals(
            [string]$frozenSource.jarSha256, $fixedJarSha256,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "TEST_INVALID_ARTIFACT: fixed JAR SHA-256 mismatch: $($frozenSource.jarSha256)"
    }
    Write-AtomicJson -Path $sourceFingerprintPath -Value $frozenSource
    Save-MasterState -Status 'RUNNING'
    $canary5k = Invoke-FixedRun `
        -Stage 'E-P1_CANARY_5K' -Scale 'PERFORMANCE_40K' `
        -KeepApplicationRunningAfterSuite $true `
        -ReuseExistingApplication $false `
        -DirectConcurrencyCanary $true `
        -ExpectedFormalSegmentCount 1
    # Canary 纯性能不足只记录性能结论；功能、可靠性、环境失败已由子运行抛出。
    $performance40k = Invoke-FixedRun `
        -Stage 'PERFORMANCE_40K' -Scale 'PERFORMANCE_40K' `
        -KeepApplicationRunningAfterSuite $true `
        -ReuseExistingApplication $true `
        -DirectConcurrencyCanary $false `
        -ExpectedFormalSegmentCount 8
    # 40K 纯性能失败不会阻止容量阶段；功能、数据或环境失败已由子运行抛出。
    $capacity80k = Invoke-FixedRun `
        -Stage 'CAPACITY_80K' -Scale 'CAPACITY_80K' `
        -KeepApplicationRunningAfterSuite $false `
        -ReuseExistingApplication $true `
        -DirectConcurrencyCanary $false `
        -ExpectedFormalSegmentCount 8
    $script:ownedApplicationPid = 0

    $script:currentStage = 'COMPLETE'
    $script:currentRunId = $null
    $script:currentChildPid = $null
    $overallContractPassed = $canary5k.qpsPassed -and
        $performance40k.qpsPassed -and $capacity80k.qpsPassed
    $eightSegmentContinuity = if ($performance40k.qpsPassed -and $capacity80k.qpsPassed) {
        '全部持续达标'
    } else { '部分区段性能回退' }
    Save-MasterState -Status $(if ($overallContractPassed) { 'PASS' } else { 'PASS_WITH_WARNINGS' })
    @"
# 会员订单 ORDER_CREATE 优化与扩容复测结论

- 合同门槛：$(if ($overallContractPassed) { 'PASS' } else { '性能WARN' })
- 黄金基线复现：$($canary5k.goldenBaselineConclusion)
- E-P1 Canary：合同$($canary5k.contractVerdict)；黄金$($canary5k.goldenBaselineConclusion)
- 40K充分预热正式测试：合同$($performance40k.contractVerdict)；黄金$($performance40k.goldenBaselineConclusion)
- 80K充分预热容量测试：合同$($capacity80k.contractVerdict)；黄金能力$($capacity80k.goldenBaselineConclusion)
- 八段持续性：$eightSegmentContinuity
- Redis Pipeline：$RedisWriteBatchSize
- Redis lane：$RedisWriteLaneCount
- Redis 总逻辑在途：$RedisWriteMaximumInflight
- 已完成子运行：$($runs.Count)
"@ | Set-Content -LiteralPath $finalVerdictPath -Encoding UTF8
} catch {
    $failureMessage = $_.Exception.Message
    $testInvalid = $failureMessage -match 'TEST_INVALID_EVIDENCE_PUBLICATION|Access to the path is denied|sharing violation|lock violation|TEST_INVALID_|JAR|Source fingerprint changed|source.*drift|worktree.*drift|configuration drift|sampler|being used by another process|\u65e0\u6cd5\u521b\u5efa\u8be5\u6587\u4ef6'
    $performanceFailure = $failureMessage -match 'Warmup attempt 2 did not satisfy|PREHEAT_INSUFFICIENT'
    $failureContract = if ($testInvalid) {
        '测试无效'
    } elseif ($performanceFailure) { '性能FAIL' } else { '功能FAIL' }
    $failureGolden = if ($testInvalid) {
        '不裁决（测试无效或未执行）'
    } elseif ($performanceFailure) { '未复现且不达标' } else { '不裁决（测试无效或未执行）' }
    $canaryCompleted = @($runs | Where-Object stage -eq 'E-P1_CANARY_5K')
    $performanceCompleted = @($runs | Where-Object stage -eq 'PERFORMANCE_40K')
    $capacityCompleted = @($runs | Where-Object stage -eq 'CAPACITY_80K')
    $canaryText = if ($canaryCompleted.Count -eq 1) {
        "合同$($canaryCompleted[0].contractVerdict)；黄金$($canaryCompleted[0].goldenBaselineConclusion)"
    } elseif ($currentStage -eq 'E-P1_CANARY_5K') { $failureContract } else { '未执行' }
    $performanceText = if ($performanceCompleted.Count -eq 1) {
        "合同$($performanceCompleted[0].contractVerdict)；黄金$($performanceCompleted[0].goldenBaselineConclusion)"
    } elseif ($currentStage -eq 'PERFORMANCE_40K') { $failureContract } else { '未执行' }
    $capacityText = if ($capacityCompleted.Count -eq 1) {
        "合同$($capacityCompleted[0].contractVerdict)；黄金$($capacityCompleted[0].goldenBaselineConclusion)"
    } elseif ($currentStage -eq 'CAPACITY_80K') { $failureContract } else { '未执行' }
    Stop-MasterOwnedApplication
    $script:currentStage = 'STOPPED'
    $script:currentChildPid = $null
    Save-MasterState -Status $(if ($testInvalid) { 'TEST_INVALID' } else { 'FAIL' })
    @"
# 会员订单 ORDER_CREATE 优化与扩容复测结论

- 合同门槛：$failureContract
- 黄金基线复现：$failureGolden
- E-P1 Canary：$canaryText
- 40K充分预热正式测试：$performanceText
- 80K充分预热容量测试：$capacityText
- 八段持续性：未完成
- 停止原因：$failureMessage
- 已生成证据均保留，未清理正式数据库数据。
"@ | Set-Content -LiteralPath $finalVerdictPath -Encoding UTF8
    throw
}
