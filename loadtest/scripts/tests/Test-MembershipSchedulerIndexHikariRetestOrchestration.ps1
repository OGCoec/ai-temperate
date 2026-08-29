[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$orchestratorPath = Join-Path $PSScriptRoot `
    '..\Start-MembershipSchedulerIndexHikariRetest.ps1'
$probePath = Join-Path $PSScriptRoot '..\Invoke-MembershipLatestPaidIndexProbe.ps1'
$suiteChildPath = Join-Path $PSScriptRoot `
    '..\Invoke-MembershipMillisecondBoundarySuiteChild.ps1'
$launcherPath = Join-Path $PSScriptRoot '..\Start-MembershipLoadtestApplication.ps1'
foreach ($path in @($orchestratorPath, $probePath, $suiteChildPath, $launcherPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Formal retest orchestration artifact is missing: $path"
    }
}

$orchestrator = Get-Content -Raw -LiteralPath $orchestratorPath
foreach ($required in @(
        'membership-order-create-',
        '[int] $PostgresMaxConnections = 384',
        '[int] $HikariMaximumPoolSize = 256',
        '[int] $HikariMinimumIdle = 8',
        '[int] $MaximumNavicatConnections = 8',
        '[ValidateSet(0, 120, 600)]',
        '[int] $PostgresStabilitySeconds = 120',
        "[ValidateSet('PERFORMANCE_40K', 'CAPACITY_80K')]",
        '[int] $RedisWriteBatchSize = 64',
        '[int] $RedisWriteLaneCount = 6',
        '[int] $RedisWriteMaximumInflight = 384',
        '[switch] $DirectConcurrencyCanary',
        '$segments = @(if ($DirectConcurrencyCanary)',
        '$expectedRunOrders = $expectedRowsPerSegment * $segments.Count',
        'directConcurrencyCanary = [bool]$DirectConcurrencyCanary',
        'postgresStabilitySeconds = $PostgresStabilitySeconds',
        'direct-concurrency-canary-verdict.json',
        'Start-MembershipLoadtestApplication.ps1',
        '-PostgresPoolMaximumSize $HikariMaximumPoolSize',
        '-PostgresPoolMinimumIdle $HikariMinimumIdle',
        'Measure-MembershipPaymentRuntimeEvidence.ps1',
        "'-PostgresMaxConnections',[string]`$PostgresMaxConnections",
        "'-MaximumNavicatConnections',[string]`$MaximumNavicatConnections",
        "'-HikariMaximumPoolSize',[string]`$HikariMaximumPoolSize",
        "'-HikariMinimumIdle',[string]`$HikariMinimumIdle",
        "'-RedisWriteBatchSize',[string]`$RedisWriteBatchSize",
        "'-RedisWriteLaneCount',[string]`$RedisWriteLaneCount",
        "'-RedisWriteMaximumInflight',[string]`$RedisWriteMaximumInflight",
        'navicat-observer-baseline.json',
        'Wait-EvidenceSamplerReady',
        'scheduler-queue-samples.csv',
        "'POSTGRES_STABILITY'",
        '$allowedEvidenceGapSeconds = 15D',
        '$allowedEvidenceGapSeconds = 5D',
        'hikari-runtime-samples.csv',
        'redis-write-runtime-samples.csv',
        'host-runtime-samples.csv',
        'Invoke-MembershipMillisecondBoundarySuiteChild.ps1',
        'Invoke-MembershipLatestPaidIndexProbe.ps1',
        'New-MembershipSchedulerIndexHikariReport.ps1',
        'membership-payment-thread-dump.txt',
        'latest-paid-index-before.txt',
        'latest-paid-index-after.txt',
        'orchestrator-failure.json',
        'run-state.json',
        'heartbeat.json',
        '$temporaryPath = "$Path.$PID.$([guid]::NewGuid().ToString(''N'')).partial"',
        '$stream.Flush($true)',
        '[IO.File]::Move($temporaryPath, $Path, $true)',
        '$retryClock = [Diagnostics.Stopwatch]::StartNew()',
        '$nativeError -in @(5, 32, 33)',
        'TEST_INVALID_EVIDENCE_PUBLICATION: JSON destination remained locked for 10 seconds',
        'function Read-JsonSnapshot',
        '[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete',
        '$state = Read-JsonSnapshot -Path $stateFile',
        'Save-Heartbeat',
        'formal-log-archive-manifest.json',
        'Assert-SourceAndJarUnchanged',
        'evidence-sampler.stop',
        'if ($null -eq $suite) { return }',
        '/millisecond-boundary/state',
        'if ([bool]$runtime.hikari.poolAvailable)',
        'finally')) {
    if (-not $orchestrator.Contains($required)) {
        throw "Formal retest orchestrator contract is missing: $required"
    }
}

$suiteChild = Get-Content -Raw -LiteralPath $suiteChildPath
if (-not $suiteChild.Contains('Start-MembershipMillisecondBoundarySuite.ps1') -or
        -not $suiteChild.Contains('CreationConcurrency = 256') -or
        -not $suiteChild.Contains('HttpConcurrency = 256') -or
        -not $suiteChild.Contains('PaymentConcurrency = 56') -or
        -not $suiteChild.Contains('WarmupOrderCount = 0') -or
        -not $suiteChild.Contains('PrecheckSeconds = 0') -or
        -not $suiteChild.Contains('PostgresStabilitySeconds') -or
        -not $suiteChild.Contains('InterSegmentSeconds = 120') -or
        -not $suiteChild.Contains('PostgresMaxConnections') -or
        -not $suiteChild.Contains('HikariMaximumPoolSize') -or
        -not $suiteChild.Contains('HikariMinimumIdle') -or
        -not $suiteChild.Contains('RunScale') -or
        -not $suiteChild.Contains('RedisWriteBatchSize') -or
        -not $suiteChild.Contains('RedisWriteLaneCount') -or
        -not $suiteChild.Contains('RedisWriteMaximumInflight') -or
        -not $suiteChild.Contains('directConcurrencyCanary') -or
        -not $suiteChild.Contains('$arguments.DirectConcurrencyCanary = $true')) {
    throw 'The isolated Suite child does not preserve the fixed formal parameters.'
}
if ($suiteChild.Contains('WarmupOrderCount = 8') -or
        $suiteChild.Contains('PrecheckSeconds = 120')) {
    throw 'The isolated Suite child still forwards the obsolete tiny warmup or fixed empty precheck.'
}

$launcher = Get-Content -Raw -LiteralPath $launcherPath
foreach ($required in @(
        '[int] $PostgresPoolMaximumSize = 256',
        '[int] $PostgresPoolMinimumIdle = 8',
        "POSTGRES_POOL_MAXIMUM_SIZE",
        "POSTGRES_POOL_MINIMUM_IDLE")) {
    if (-not $launcher.Contains($required)) {
        throw "Application launcher connection contract is missing: $required"
    }
}
if ($orchestrator.Contains('Stop-Process -Name') -or
        $orchestrator.Contains('Remove-Item -Recurse')) {
    throw 'Formal retest orchestrator contains a broad destructive operation.'
}
if ($orchestrator -match 'foreach\s*\(\s*\$pid\b' -or
        $orchestrator -match '\[[A-Za-z0-9_.]+\]\s*\$Pid\b') {
    throw 'Formal retest orchestrator attempts to overwrite the read-only PID variable.'
}

$probe = Get-Content -Raw -LiteralPath $probePath
foreach ($required in @(
        'ANALYZE membership_order',
        '/millisecond-boundary/tokens/',
        '/api/user/membership-plan-offers',
        'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)',
        'idx_membership_order_latest_paid',
        'latest-paid-upgrade-probe.json',
        'latest-paid-order-explain.json')) {
    if (-not $probe.Contains($required)) {
        throw "Latest PAID probe contract is missing: $required"
    }
}
if ($probe -match '(?i)accessToken\s*=.*Set-Content|Authorization.*Export-Csv') {
    throw 'Latest PAID probe appears to persist an access token.'
}
if (-not $probe.Contains('70000000000079999') -or
        -not $probe.Contains('$tokenPage -gt 159')) {
    throw 'Latest PAID probe does not cover the complete fixed 80K fixture.'
}
$reportSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot `
    '..\New-MembershipSchedulerIndexHikariReport.ps1')
if (-not $reportSource.Contains('[ValidateRange(1, 10000)]') -or
        -not $reportSource.Contains('redis-write-runtime-samples.csv')) {
    throw 'Special report does not accept 10K segments or require Redis lane evidence.'
}

Write-Output 'PASS: formal scheduler/index/Hikari retest orchestration contract is complete.'
