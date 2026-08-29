[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$samplerPath = Join-Path $PSScriptRoot '..\Measure-MembershipPaymentRuntimeEvidence.ps1'
$queueRuntimeSamplerPath = Join-Path $PSScriptRoot `
    '..\Measure-MembershipPaymentQueueRuntimeEvidence.ps1'
if (-not (Test-Path -LiteralPath $samplerPath -PathType Leaf)) {
    throw "Runtime evidence sampler is missing: $samplerPath"
}
if (-not (Test-Path -LiteralPath $queueRuntimeSamplerPath -PathType Leaf)) {
    throw "Queue/runtime evidence sampler is missing: $queueRuntimeSamplerPath"
}
$source = Get-Content -Raw -LiteralPath $samplerPath
foreach ($required in @(
        '[string] $RunId',
        '[int] $AppPid',
        '[int] $Port = 6655',
        '[int] $HikariMaximumPoolSize = 96',
        '[int] $HikariMinimumIdle = 8',
        '[int] $RedisWriteBatchSize = 64',
        '[int] $RedisWriteLaneCount = 6',
        '[int] $RedisWriteMaximumInflight = 384',
        '[int] $PostgresMaxConnections = 384',
        '[int] $MaximumNavicatConnections = 8',
        'MembershipSchedulerIndexHikariEvidence.psm1',
        'host-runtime-samples.csv',
        'postgres-connection-samples.raw.txt',
        'postgres-connection-samples.csv',
        'evidence-sampler-failure.json',
        'Measure-MembershipPaymentQueueRuntimeEvidence.ps1',
        "'-HikariMaximumPoolSize',[string]`$HikariMaximumPoolSize",
        "'-HikariMinimumIdle',[string]`$HikariMinimumIdle",
        "'-RedisWriteBatchSize',[string]`$RedisWriteBatchSize",
        "'-RedisWriteLaneCount',[string]`$RedisWriteLaneCount",
        "'-RedisWriteMaximumInflight',[string]`$RedisWriteMaximumInflight",
        'queue-runtime-sampler-failure.json',
        '-WindowStyle Hidden',
        'Convert-MembershipPostgresWatchOutput',
        'navicatTotal',
        'navicatActive',
        'navicatIdleInTransaction',
        'navicatWriteOrDdl',
        'finally')) {
    if (-not $source.Contains($required)) {
        throw "Runtime evidence sampler contract is missing: $required"
    }
}

$moduleSource = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot `
    '..\MembershipSchedulerIndexHikariEvidence.psm1')
foreach ($required in @(
        'navicatTotal',
        'navicatActive',
        'navicatIdleInTransaction',
        'navicatWriteOrDdl')) {
    if (-not $moduleSource.Contains($required)) {
        throw "PostgreSQL observer evidence contract is missing: $required"
    }
}
if ($source.Contains('KEYS *') -or $source.Contains('Stop-Process -Name')) {
    throw 'Runtime evidence sampler contains a broad or unsafe operation.'
}

$queueRuntimeSource = Get-Content -Raw -LiteralPath $queueRuntimeSamplerPath
foreach ($required in @(
        '[int] $QueueIntervalMillis = 500',
        '[int] $RuntimeIntervalMillis = 1000',
        '[int] $HikariMaximumPoolSize = 96',
        '[int] $HikariMinimumIdle = 8',
        '[int] $RedisWriteBatchSize = 64',
        '[int] $RedisWriteLaneCount = 6',
        '[int] $RedisWriteMaximumInflight = 384',
        'System.Net.Http.SocketsHttpHandler',
        'MaxConnectionsPerServer = 1',
        '/loadtest-inspection/queues',
        '/loadtest-inspection/runtime',
        'membership.payment.check.queue',
        'membership.closing.check.queue',
        'RABBITMQ_DEFAULT_USER',
        'RABBITMQ_DEFAULT_PASS',
        '$rabbitQueues = @($rabbitQueueResponse)',
        'scheduler-queue-samples.csv',
        'hikari-runtime-samples.csv',
        'redis-write-runtime-samples.csv',
        'hikari-metrics-baseline.json',
        'hikari-metrics-final.json',
        '$runtime.hikari.configuredMaximumPoolSize -ne',
        '$runtime.hikari.configuredMinimumIdle -ne',
        '$RuntimeProbe.redisWrite.configuredBatchSize -ne',
        '$RuntimeProbe.redisWrite.configuredLaneCount -ne',
        '$RuntimeProbe.redisWrite.maximumInflight -ne $RedisWriteMaximumInflight',
        '$RuntimeProbe.redisWrite.fullRestoreQueueDepths',
        '$RuntimeProbe.redisWrite.paymentAttemptPatchQueueDepths',
        'lane0QueueDepth',
        'lane1QueueDepth',
        'lane2QueueDepth',
        'lane3QueueDepth',
        'lane4QueueDepth',
        'lane5QueueDepth',
        'lane0FullRestoreQueueDepth',
        'lane1FullRestoreQueueDepth',
        'lane2FullRestoreQueueDepth',
        'lane3FullRestoreQueueDepth',
        'lane4FullRestoreQueueDepth',
        'lane5FullRestoreQueueDepth',
        'lane0PaymentAttemptPatchQueueDepth',
        'lane1PaymentAttemptPatchQueueDepth',
        'lane2PaymentAttemptPatchQueueDepth',
        'lane3PaymentAttemptPatchQueueDepth',
        'lane4PaymentAttemptPatchQueueDepth',
        'lane5PaymentAttemptPatchQueueDepth',
        'queue-runtime-sampler-failure.json',
        'finally')) {
    if (-not $queueRuntimeSource.Contains($required)) {
        throw "Queue/runtime sampler contract is missing: $required"
    }
}
if ($queueRuntimeSource.Contains('Stop-Process -Name')) {
    throw 'Queue/runtime sampler contains a broad process stop.'
}

Write-Output 'PASS: runtime evidence sampler process contract is complete.'
