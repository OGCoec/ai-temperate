[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$inputPath = Join-Path $repositoryRoot 'loadtest\input\membership-millisecond-boundary-groups.csv'
$capacityInputPath = Join-Path $repositoryRoot `
    'loadtest\input\membership-millisecond-boundary-groups-80k.csv'
$jmxPath = Join-Path $repositoryRoot 'loadtest\jmeter\membership-millisecond-boundary.jmx'
$driverPath = Join-Path $repositoryRoot 'loadtest\scripts\jmeter\membership-millisecond-boundary.groovy'
$waveRunnerPath = Join-Path $repositoryRoot 'loadtest\scripts\Invoke-MembershipMillisecondBoundaryWave.ps1'
$redisHelperPath = Join-Path $repositoryRoot 'loadtest\scripts\MembershipBoundaryRedis.ps1'
$jtlGatePath = Join-Path $repositoryRoot 'loadtest\scripts\Assert-MembershipJmeterResults.ps1'
$suitePath = Join-Path $repositoryRoot 'loadtest\scripts\Start-MembershipMillisecondBoundarySuite.ps1'
$interSegmentModulePath = Join-Path $repositoryRoot `
    'loadtest\scripts\MembershipInterSegmentStability.psm1'
$interSegmentGateTestPath = Join-Path $repositoryRoot `
    'loadtest\scripts\tests\Test-MembershipInterSegmentDelayedMessageGate.ps1'
$applicationLauncherPath = Join-Path $repositoryRoot 'loadtest\scripts\Start-MembershipLoadtestApplication.ps1'
$timingReporterPath = Join-Path $repositoryRoot `
    'loadtest\scripts\New-MembershipPaymentFocusedTimingReport.ps1'
$focusedTimingTestPath = Join-Path $repositoryRoot `
    'loadtest\scripts\tests\Test-MembershipPaymentFocusedTimingReport.ps1'
$httpReporterPath = Join-Path $repositoryRoot `
    'loadtest\scripts\New-MembershipOrderCreateHttpReport.ps1'
$httpReporterTestPath = Join-Path $repositoryRoot `
    'loadtest\scripts\tests\Test-MembershipOrderCreateHttpReport.ps1'
$warmupReporterPath = Join-Path $repositoryRoot `
    'loadtest\scripts\New-MembershipWarmupStabilityReport.ps1'
$warmupReporterTestPath = Join-Path $repositoryRoot `
    'loadtest\scripts\tests\Test-MembershipWarmupStabilityReport.ps1'
$sameScaleWarmupTestPath = Join-Path $repositoryRoot `
    'loadtest\scripts\tests\Test-MembershipSameScaleWarmupOrchestration.ps1'
$waveSqlPath = Join-Path $repositoryRoot 'loadtest\sql\verify-membership-millisecond-boundary-wave.sql'
$orderDdlPath = Join-Path $repositoryRoot 'sql\018_create_membership_order.sql'
$callbackDdlPath = Join-Path $repositoryRoot 'sql\019_create_membership_payment_callback.sql'
$timestampMigrationPath = Join-Path $repositoryRoot `
    'sql\migrations\033_set_membership_payment_timestamp_microsecond_precision.sql'
$allArtifacts = @(
    $inputPath,
    $capacityInputPath,
    $jmxPath,
    $driverPath,
    $waveRunnerPath,
    $redisHelperPath,
    $jtlGatePath,
    $suitePath,
    $interSegmentModulePath,
    $interSegmentGateTestPath,
    $applicationLauncherPath,
    $timingReporterPath,
    $focusedTimingTestPath,
    $httpReporterPath,
    $httpReporterTestPath,
    $warmupReporterPath,
    $warmupReporterTestPath,
    $sameScaleWarmupTestPath,
    $waveSqlPath,
    $orderDdlPath,
    $callbackDdlPath
    $timestampMigrationPath
)

foreach ($path in $allArtifacts) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing millisecond-boundary artifact: $path"
    }
}

$orderDdl = Get-Content -Raw -LiteralPath $orderDdlPath
foreach ($column in @(
    'payment_started_at', 'expires_at', 'closing_deadline_at', 'paid_at',
    'entitlement_resolved_at', 'created_at', 'updated_at')) {
    if ($orderDdl -notmatch "(?m)^\s*$column\s+TIMESTAMPTZ\(6\)") {
        throw "membership_order.$column must explicitly use TIMESTAMPTZ(6)."
    }
}
$callbackDdl = Get-Content -Raw -LiteralPath $callbackDdlPath
foreach ($column in @('paid_at', 'received_at', 'resolved_at')) {
    if ($callbackDdl -notmatch "(?m)^\s*$column\s+TIMESTAMPTZ\(6\)") {
        throw "membership_payment_callback.$column must explicitly use TIMESTAMPTZ(6)."
    }
}
$timestampMigration = Get-Content -Raw -LiteralPath $timestampMigrationPath
foreach ($fragment in @(
    'DROP VIEW IF EXISTS public.membership_order_readable',
    'DROP VIEW IF EXISTS public.membership_payment_callback_readable',
    'CREATE OR REPLACE VIEW public.membership_order_readable',
    'CREATE OR REPLACE VIEW public.membership_payment_callback_readable')) {
    if (-not $timestampMigration.Contains($fragment)) {
        throw "Timestamp migration does not preserve readable views: $fragment"
    }
}

$rows = @(Import-Csv -LiteralPath $inputPath)
if ($rows.Count -ne 8) {
    throw "Millisecond-boundary input must contain exactly eight groups; received $($rows.Count)."
}

$expected = @(
    @{ code = 'E-P1'; wave = 'E-PRE'; reference = 'EXPIRES_AT'; first = 70000000000000000L; last = 70000000000004999L; offset = -1L; step = 0L },
    @{ code = 'E-PR'; wave = 'E-PRE'; reference = 'EXPIRES_AT'; first = 70000000000005000L; last = 70000000000009999L; offset = -1000L; step = 2L },
    @{ code = 'E-A1'; wave = 'E-AFTER'; reference = 'EXPIRES_AT'; first = 70000000000010000L; last = 70000000000014999L; offset = 1L; step = 0L },
    @{ code = 'E-AR'; wave = 'E-AFTER'; reference = 'EXPIRES_AT'; first = 70000000000015000L; last = 70000000000019999L; offset = 0L; step = 2L },
    @{ code = 'H-P1'; wave = 'H-PRE'; reference = 'HARD_CLOSE_AT'; first = 70000000000020000L; last = 70000000000024999L; offset = -1L; step = 0L },
    @{ code = 'H-PR'; wave = 'H-PRE'; reference = 'HARD_CLOSE_AT'; first = 70000000000025000L; last = 70000000000029999L; offset = -1000L; step = 2L },
    @{ code = 'H-A1'; wave = 'H-AFTER'; reference = 'HARD_CLOSE_AT'; first = 70000000000030000L; last = 70000000000034999L; offset = 1L; step = 0L },
    @{ code = 'H-AR'; wave = 'H-AFTER'; reference = 'HARD_CLOSE_AT'; first = 70000000000035000L; last = 70000000000039999L; offset = 0L; step = 2L }
)

for ($index = 0; $index -lt $expected.Count; $index += 1) {
    $row = $rows[$index]
    $wanted = $expected[$index]
    if ($row.groupCode -ne $wanted.code -or
        $row.waveCode -ne $wanted.wave -or
        $row.boundaryReference -ne $wanted.reference -or
        [long]$row.firstUserId -ne $wanted.first -or
        [long]$row.lastUserId -ne $wanted.last -or
        [int]$row.userCount -ne 5000 -or
        [long]$row.firstOffsetMillis -ne $wanted.offset -or
        [long]$row.offsetStepMillis -ne $wanted.step -or
        [int]$row.offsetCycleSize -ne 500 -or
        [int]$row.usersPerTier -ne 1250 -or
        $row.tierOrder -ne 'GO|PLUS|PRO|MAX' -or
        [long]$row.teamProbeStartUserId -ne $wanted.first -or
        [int]$row.teamProbeCount -ne 25) {
        throw "Group $($wanted.code) does not match the immutable 8x5,000 boundary contract."
    }
}

if ((($rows | Measure-Object -Property userCount -Sum).Sum) -ne 40000) {
    throw 'Millisecond-boundary contract must total exactly 40,000 users.'
}
$capacityRows = @(Import-Csv -LiteralPath $capacityInputPath)
if ($capacityRows.Count -ne 8 -or
        (($capacityRows | Measure-Object -Property userCount -Sum).Sum) -ne 80000) {
    throw 'Capacity boundary contract must contain exactly eight 10,000-user groups.'
}
for ($index = 0; $index -lt $capacityRows.Count; $index += 1) {
    $row = $capacityRows[$index]
    if ($row.groupCode -ne $expected[$index].code -or
            [long]$row.firstUserId -ne 70000000000000000L + 10000L * $index -or
            [long]$row.lastUserId -ne 70000000000009999L + 10000L * $index -or
            [int]$row.userCount -ne 10000 -or [int]$row.usersPerTier -ne 2500 -or
            [int]$row.offsetCycleSize -ne 500 -or [int]$row.teamProbeCount -ne 25) {
        throw "Capacity group $($expected[$index].code) does not match the immutable 8x10,000 contract."
    }
}
foreach ($wave in @('E-PRE', 'E-AFTER', 'H-PRE', 'H-AFTER')) {
    $waveRows = @($rows | Where-Object waveCode -eq $wave)
    if ($waveRows.Count -ne 2 -or (($waveRows | Measure-Object -Property userCount -Sum).Sum) -ne 10000) {
        throw "Wave $wave must contain exactly two groups and 10,000 users."
    }
}

$sources = @{}
foreach ($path in @(
    $jmxPath, $driverPath, $waveRunnerPath, $redisHelperPath, $suitePath,
    $interSegmentModulePath, $waveSqlPath, $applicationLauncherPath, $timingReporterPath)) {
    $sources[$path] = Get-Content -Raw -LiteralPath $path
    if ($sources[$path] -match '(?i)loadtest-fast' -or $sources[$path] -match "(?<!\d)8080(?!\d)") {
        throw "Forbidden fast profile or port 8080 found in $path"
    }
}

foreach ($fragment in @(
    'Invoke-MembershipBoundaryRedisCli',
    'REDISCLI_AUTH',
    '--requirepass')) {
    if (-not $sources[$redisHelperPath].Contains($fragment)) {
        throw "Redis helper is missing authenticated inspection fragment: $fragment"
    }
}
foreach ($fragment in @(
    'Remove-MembershipBoundaryRedisOrderArtifacts',
    '[string[]] $CallbackIds = @()',
    '[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)',
    '[Math]::Min(100,',
    "@('UNLINK') + `$batch",
    "@('ZREM', `$key) + `$batch",
    'ait:*:payment:membership-order:v[12]:snapshot:*',
    'ait:*:payment:callback:v[12]:data:*')) {
    if (-not $sources[$redisHelperPath].Contains($fragment)) {
        throw "Redis helper is missing exact run-owned artifact cleanup: $fragment"
    }
}
foreach ($path in @($waveRunnerPath, $suitePath)) {
    if (-not $sources[$path].Contains('MembershipBoundaryRedis.ps1') -or
        -not $sources[$path].Contains('Invoke-MembershipBoundaryRedisCli')) {
        throw "Boundary runner does not use the authenticated Redis helper: $path"
    }
}

foreach ($fragment in @(
    'Executors.newVirtualThreadPerTaskExecutor()',
    'HttpClient.newBuilder()',
    'BodyHandlers.ofString(StandardCharsets.UTF_8)',
    'creationConcurrency',
    'httpConcurrency',
    'paymentConcurrency',
    'Semaphore httpLimiter = new Semaphore(httpConcurrency, true)',
    'httpLimiter.acquire()',
    'httpLimiter.release()',
    'Semaphore paymentLimiter = new Semaphore(paymentConcurrency)',
    'boolean creationAcquired = false',
    'boolean paymentAcquired = false',
    'offsetCycleSize',
    'position % offsetCycleSize',
    'MessageDigest',
    '0x40',
    '0x80',
    'targetOffsetMillis',
    'SCENARIO_ORDERS_CSV',
    'CALLBACK_DISPATCH_CSV')) {
    if (-not $sources[$driverPath].Contains($fragment)) {
        throw "JMeter Groovy driver is missing contract fragment: $fragment"
    }
}
$httpAcquireIndex = $sources[$driverPath].IndexOf('httpLimiter.acquire()')
$httpRequestIndex = $sources[$driverPath].IndexOf(
    'response = request(method, path, headers, body)')
$httpReleaseIndex = $sources[$driverPath].IndexOf('httpLimiter.release()')
if ($httpAcquireIndex -lt 0 -or $httpRequestIndex -lt 0 -or $httpReleaseIndex -lt 0 -or
        $httpAcquireIndex -gt $httpRequestIndex -or $httpReleaseIndex -lt $httpRequestIndex) {
    throw 'Every real HTTP attempt must remain inside the shared 256-permit boundary.'
}
$creationReleaseIndex = $sources[$driverPath].IndexOf('creationLimiter.release()')
$paymentRequestIndex = $sources[$driverPath].IndexOf("'/payment-attempts'")
if ($creationReleaseIndex -lt 0 -or $paymentRequestIndex -lt 0 -or
        $creationReleaseIndex -gt $paymentRequestIndex) {
    throw 'ORDER_CREATE capacity must be released before PAYMENT_ATTEMPT begins.'
}
foreach ($fragment in @(
    "props.getProperty('CONNECT_ATTEMPTS', '3')",
    'catch (ConnectException failure)',
    'ThreadLocalRandom.current().nextLong',
    'transport_attempts')) {
    if (-not $sources[$driverPath].Contains($fragment)) {
        throw "JMeter Groovy driver is missing bounded connect retry evidence: $fragment"
    }
}
foreach ($forbiddenHttpFragment in @('HttpURLConnection', 'connection.disconnect()')) {
    if ($sources[$driverPath].Contains($forbiddenHttpFragment)) {
        throw "JMeter Groovy driver must reuse one shared HTTP client: $forbiddenHttpFragment"
    }
}
if (-not $sources[$waveRunnerPath].Contains('[int] $CreationConcurrency = 256')) {
    throw 'Boundary wave runner must default order creation concurrency to 256.'
}
if (-not $sources[$waveRunnerPath].Contains('[int] $PaymentConcurrency = 56')) {
    throw 'Boundary wave runner must default payment attempt concurrency to 56.'
}
foreach ($fragment in @(
    '[int] $CreationConcurrency = 256',
    '[int] $HttpConcurrency = 256',
    '[int] $PaymentConcurrency = 56',
    '[ValidateSet(256)]',
    '[ValidateSet(56)]',
    '[int] $RedisWriteBatchSize = 64',
    '[int] $RedisWriteLaneCount = 6',
    '[int] $RedisWriteMaximumInflight = 384',
    'redisWriteBatchSize = $RedisWriteBatchSize',
    'redisWriteLaneCount = $RedisWriteLaneCount',
    'redisWriteMaximumInflight = $RedisWriteMaximumInflight')) {
    if (-not $sources[$suitePath].Contains($fragment)) {
        throw "Boundary suite is missing its 64x6/384 Redis or 256 HTTP concurrency contract: $fragment"
    }
}
foreach ($fragment in @(
    'function Write-AtomicJson',
    '$temporaryPath = "$Path.$PID.$([guid]::NewGuid().ToString(''N'')).partial"',
    '$stream.Flush($true)',
    '[IO.File]::Move($temporaryPath, $Path, $true)',
    '$retryClock = [Diagnostics.Stopwatch]::StartNew()',
    '$nativeError -in @(5, 32, 33)',
    'TEST_INVALID_EVIDENCE_PUBLICATION: JSON destination remained locked for 10 seconds',
    'function Read-JsonSnapshot',
    '[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete',
    'Read-JsonSnapshot -Path $segmentHeartbeatPath',
    'Write-AtomicJson -Path $statePath -Value',
    'being used by another process|\u65e0\u6cd5\u521b\u5efa\u8be5\u6587\u4ef6')) {
    if (-not $sources[$suitePath].Contains($fragment)) {
        throw "Boundary suite is missing atomic soak-state publication: $fragment"
    }
}
if ($sources[$suitePath].Contains(
        'Set-Content -LiteralPath $statePath -Encoding UTF8')) {
    throw 'Boundary suite must not directly overwrite soak-state.json.'
}
foreach ($fragment in @(
    "props.getProperty('CREATION_CONCURRENCY', '256')",
    "props.getProperty('HTTP_CONCURRENCY', '256')",
    "props.getProperty('PAYMENT_CONCURRENCY', '56')",
    'creationConcurrency != 256 || httpConcurrency != 256 || paymentConcurrency != 56')) {
    if (-not $sources[$driverPath].Contains($fragment)) {
        throw "JMeter Groovy driver is missing the 256 concurrency contract: $fragment"
    }
}
foreach ($obsolete in @(
    "props.getProperty('CREATION_CONCURRENCY', '4096')",
    "props.getProperty('HTTP_CONCURRENCY', '4096')",
    '[int] $CreationConcurrency = 4096',
    '[int] $HttpConcurrency = 4096',
    '[int] $PaymentConcurrency = 256')) {
    if ($sources[$driverPath].Contains($obsolete) -or
        $sources[$waveRunnerPath].Contains($obsolete) -or
        $sources[$suitePath].Contains($obsolete)) {
        throw "Millisecond boundary scripts still contain an obsolete 4096 concurrency default: $obsolete"
    }
}
foreach ($fragment in @(
    '[int]$row[0].consumers -ne 48',
    'does not have exactly 48 consumers')) {
    if (-not $sources[$waveRunnerPath].Contains($fragment)) {
        throw "Wave Runner is missing the 48-consumer runtime gate: $fragment"
    }
}
if ($sources[$waveRunnerPath].Contains('does not have exactly one consumer') -or
    $sources[$waveRunnerPath].Contains('[int]$row[0].consumers -ne 1')) {
    throw 'Wave Runner still contains the obsolete single-consumer runtime gate.'
}
foreach ($fragment in @(
    "DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss.SSSSSS')",
    "String tradeNo = order.groupCode + '-MMB-' + runId + '-' + order.userId",
    "tradeNo.length() > 128",
    'REQUEST_RESULTS_CSV',
    'CountDownLatch(expectedUsers)',
    'X-Trace-Id',
    'X-Loadtest-Run-Id',
    'X-Loadtest-Segment',
    'trace_id',
    'dispatch_drift_micros',
    'Math.floorDiv(Duration.between(order.targetAt, startedAt).toNanos(), 1_000L)')) {
    if (-not $sources[$driverPath].Contains($fragment)) {
        throw "JMeter Groovy driver is missing microsecond or concurrency fragment: $fragment"
    }
}
if ($sources[$driverPath].Contains(
        'Duration.between(order.targetAt, startedAt).toNanos() / 1_000L')) {
    throw 'Boundary JMeter driver must not emit fractional microseconds through Groovy division.'
}
if ($sources[$driverPath].Contains('UUID.nameUUIDFromBytes')) {
    throw 'Boundary JMeter driver must generate canonical UUIDv4 idempotency keys.'
}
foreach ($fragment in @('6655', 'loadtest-realtime', 'membership-millisecond-boundary.groovy')) {
    if (-not $sources[$jmxPath].Contains($fragment)) {
        throw "JMX is missing contract fragment: $fragment"
    }
}
foreach ($fragment in @(
    'callback.received_at < test.planned_hard_close_at',
    "'__BOUNDARY_VERDICT_MODE__'::TEXT AS boundary_verdict_mode",
    "boundary_verdict_mode = 'STRICT_SERVER_TIME'",
    "boundary_verdict_mode = 'TERMINAL_OUTCOME'",
    "callback_resolution NOT IN ('APPLIED', 'REFUND_REQUIRED')",
    'callback_provider_trade_no IS NULL OR length(callback_provider_trade_no) > 128',
    "callback_resolution = 'REFUND_REQUIRED'",
    'order_provider_trade_no IS NOT NULL',
    'REFUND_PROVIDER_TRADE_NOT_CLEARED',
    "callback_resolution = 'APPLIED'",
    'order_provider_trade_no IS DISTINCT FROM callback_provider_trade_no',
    'entitlement_resolution IS DISTINCT FROM callback_resolution',
    '__EXPECTED_SEGMENT__',
    'closing_deadline_at IS NOT NULL',
    'closing_deadline_at IS DISTINCT FROM planned_hard_close_at',
    'server_target_drift_micros',
    'received_from_expires_micros',
    'received_from_hard_close_micros',
    'payment_started_at',
    'callback_paid_at',
    "THEN 'APPLIED'",
    "ELSE 'REFUND_REQUIRED'",
    "expected_resolution = 'APPLIED'")) {
    if (-not $sources[$waveSqlPath].Contains($fragment)) {
        throw "Wave SQL is missing server-time verdict fragment: $fragment"
    }
}
if ($sources[$waveSqlPath].Contains('callback.received_at < payment_order.closing_deadline_at') `
    -or $sources[$waveSqlPath].Contains('callback.received_at >= payment_order.closing_deadline_at')) {
    throw 'Wave SQL must adjudicate against planned_hard_close_at, not nullable closing_deadline_at.'
}
if ($sources[$waveSqlPath].Contains(
        'order_provider_trade_no IS NULL OR length(order_provider_trade_no) > 128')) {
    throw 'Wave SQL must preserve callback trade evidence when a refund clears the order trade number.'
}
foreach ($fragment in @(
    'scenario-orders.csv',
    'callback-dispatch.csv',
    'settlement-wait.csv',
    'Wait-BoundarySettlement',
    'server-time-verdict.csv',
    'time-drift.csv',
    'redis-before.json',
    'redis-after.json',
    'verdict.json',
    'sourceFingerprint',
    '$boundaryVerdictMode = if ($GroupCode -in @(''H-P1'', ''H-PR'')) {',
    "'TERMINAL_OUTCOME'",
    "'STRICT_SERVER_TIME'",
    'Replace(''__BOUNDARY_VERDICT_MODE__'', $boundaryVerdictMode)',
    '[ValidateSet(''E-P1'', ''E-PR'', ''E-A1'', ''E-AR'', ''H-P1'', ''H-PR'', ''H-A1'', ''H-AR'')]',
    'GROUP_CODE',
    '5000',
    '6655',
    '8080',
    'rabbit')) {
    if (-not $sources[$waveRunnerPath].Contains($fragment)) {
        throw "Wave Runner is missing evidence or preflight fragment: $fragment"
    }
}
foreach ($fragment in @(
    'Assert-MembershipJmeterResults.ps1',
    "-ExpectedSamplerName 'Execute Real Millisecond Boundary Wave'")) {
    if (-not $sources[$waveRunnerPath].Contains($fragment)) {
        throw "Wave Runner is missing the JTL result gate fragment: $fragment"
    }
}
foreach ($fragment in @(
    'Wait-RedisMembershipQueueDrain',
    '[int] $TimeoutSeconds = 120',
    'Start-Sleep -Milliseconds 500',
    'Save-RedisSnapshot $redisAfter -WaitForDrain')) {
    if (-not $sources[$waveRunnerPath].Contains($fragment)) {
        throw "Wave Runner is missing the bounded Redis drain gate: $fragment"
    }
}
foreach ($fragment in @(
    'E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR',
    'Assert-PostgresBoundaryBaseline',
    'Assert-RedisBoundaryBaseline',
    'Assert-RabbitBoundaryBaseline',
    'list_connections',
    'list_consumers',
    'channel_max',
    '[int]$row[0].consumers -ne 48',
    '[int]$_.prefetch_count -ne 20',
    'ait:*:payment:membership-order:v[12]:snapshot:*',
    'ait:*:payment:provider-result:v[12]:status:*',
    'account_status = 0',
    'quota_balance_minor = 5000',
    'WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000079999),',
    'JOIN membership_order payment_order ON payment_order.id = callback.order_id',
    'quota_period_started_at IS NULL',
    'membership_expires_at IS NULL',
    'git diff --binary HEAD -- @paths 2>$null',
    'callbackReadySize',
    'callbackProcessingSize',
    'dirtyProcessingSize',
    '[int] $PrecheckSeconds = 0',
    '[ValidateSet(0)]',
    '[ValidateSet(0, 120, 600)]',
    '[int] $PostgresStabilitySeconds = 120',
    '[int] $InterSegmentSeconds = 120',
    'Wait-InterSegmentStability',
    'MembershipInterSegmentStability.psm1',
    'Get-MembershipLatestHardCloseAt',
    'Get-RabbitInterSegmentObservation',
    'Update-MembershipInterSegmentGateState',
    'inter-segment-stability-samples.csv',
    'inter-segment-stability-verdict.json',
    '-ScenarioOrdersCsvPath (Join-Path $segmentOutput ''scenario-orders.csv'')',
    '-QuietSeconds $InterSegmentSeconds',
    'Assert-PostgresProcessStability',
    'pg_postmaster_start_time()',
    '\watch 1',
    'TEST_INVALID_POSTGRES',
    'postgres-stability-gate.json',
    '0xC0000142',
    'error code 487',
    '[string[]] $PreviousScenarioOrdersCsvPath',
    'Get-PreviousBoundaryResetFacts',
    'Invoke-PreviousBoundaryExactReset',
    'Assert-ResumeBoundaryState',
    'resume-boundary-receipt.json',
    'A resumed group sequence requires the original PostgreSQL stability gate.',
    'previous-callback-ids.csv',
    'previous-reset-order-ids.json',
    'previous-reset-database-facts.json',
    'previous-reset-source-manifest.json',
    'previous-reset-already-clean.json',
    "currentCounts.Trim() -eq '0|0'",
    '-CallbackIds @($resetFacts.callbackIds)',
    'StringComparer]::Ordinal',
    'expectedDatabaseFacts',
    'manifestOrderCount',
    'Save-RedisPerformanceSnapshot',
    "SLOWLOG', 'GET', '256'",
    "INFO', 'commandstats'",
    "INFO', 'latencystats'",
    'Save-ApplicationPerformanceSnapshot',
    '[int] $WarmupOrderCount = 0',
    '[switch] $DirectConcurrencyCanary',
    '$groups = @(if ($DirectConcurrencyCanary)',
    '$expectedWarmupOrders = $expectedSegmentOrders',
    'Invoke-SegmentSameScaleWarmup',
    'segment-warmup-reset',
    'New-MembershipWarmupStabilityReport.ps1',
    "warmupEvidence = 'each-group/warmup/attempt-N'",
    "@('SLOWLOG', 'RESET')",
    'formalStartedAtEpochMs',
    'MinimumCompletedAtEpochMs = $formalStartedAt.ToUnixTimeMilliseconds()',
    '$_.LocalAddress -in @(''127.0.0.1'', ''0.0.0.0'', ''::'', ''::1'')',
    'GC.heap_info',
    "-Name 'baseline'",
    "-Name 'final'",
    '[switch] $PreserveDataAfterPass',
    "preservePolicy = 'ALWAYS'",
    'dataPreserved = $true',
    'scenario-orders-all.csv',
    'callback-dispatch-all.csv',
    'request-results-all.csv',
    'final-timestamp-evidence.csv',
    'server-time-verdict.csv',
    'wave-functional-verification.json',
    'Merge-CsvFiles',
    'Write-ResetOrderManifest',
    'New-MembershipPaymentFocusedTimingReport.ps1',
    'New-MembershipOrderCreateHttpReport.ps1',
    '-RequestResultsCsvPath $allRequestResults',
    '-MinimumQps 900',
    '-MaximumWallClockSeconds $maximumFormalWallClockSeconds',
    '-MinimumEffectiveConcurrency 200',
    '-RequirePaymentOverlap',
    'MEMBERSHIP_ORDER_CREATE_HTTP_EVIDENCE_ENABLED',
    'MEMBERSHIP_ORDER_CREATE_HTTP_LOG_PATH',
    'order-create-http-verdict.json')) {
    if (-not $sources[$suitePath].Contains($fragment)) {
        throw "Suite is missing fixed orchestration fragment: $fragment"
    }
}

$strictRabbitStart = $sources[$suitePath].IndexOf('function Assert-RabbitBoundaryBaseline')
$strictRabbitEnd = $sources[$suitePath].IndexOf(
    'function Invoke-WarmupHttpRequest', $strictRabbitStart)
if ($strictRabbitStart -lt 0 -or $strictRabbitEnd -le $strictRabbitStart) {
    throw 'The strict RabbitMQ boundary baseline function is missing.'
}
$strictRabbitBlock = $sources[$suitePath].Substring(
    $strictRabbitStart, $strictRabbitEnd - $strictRabbitStart)
foreach ($fragment in @(
        'RabbitMQ membership Ready, Unacked or DLQ baseline is not empty.',
        '[int]$row[0].consumers -ne 48',
        '[int]$_.prefetch_count -ne 20')) {
    if (-not $strictRabbitBlock.Contains($fragment)) {
        throw "The strict RabbitMQ baseline was relaxed: $fragment"
    }
}
if ($strictRabbitBlock.Contains('Update-MembershipInterSegmentGateState') -or
        $strictRabbitBlock.Contains('QuietSeconds')) {
    throw 'Delay-aware behavior must not leak into the strict RabbitMQ baseline.'
}

$interSegmentStart = $sources[$suitePath].IndexOf('function Wait-InterSegmentStability')
$interSegmentEnd = $sources[$suitePath].IndexOf(
    'function Get-LatestStateMachineTimingSignature', $interSegmentStart)
if ($interSegmentStart -lt 0 -or $interSegmentEnd -le $interSegmentStart) {
    throw 'The delay-aware inter-segment wait function is missing.'
}
$interSegmentBlock = $sources[$suitePath].Substring(
    $interSegmentStart, $interSegmentEnd - $interSegmentStart)
if ($interSegmentBlock.Contains('Assert-RabbitBoundaryBaseline')) {
    throw 'The inter-segment wait must not call the instantaneous strict Rabbit baseline.'
}
foreach ($fragment in @(
        'Get-MembershipLatestHardCloseAt',
        'Update-MembershipInterSegmentGateState',
        'REDIS_QUEUE_NOT_EMPTY',
        'POSTGRES_NOT_STABLE',
        'Start-Sleep -Seconds 2')) {
    if (-not $interSegmentBlock.Contains($fragment)) {
        throw "The delay-aware inter-segment wait is missing: $fragment"
    }
}

foreach ($fragment in @(
        'RABBIT_DLQ_NOT_EMPTY',
        'RABBIT_TOPOLOGY_INVALID',
        'RABBIT_MAIN_QUEUE_SUSTAINED_BACKLOG',
        'INTER_SEGMENT_STABILITY_TIMEOUT',
        "decision = 'PASS'",
        "phase = 'WAITING_FOR_HORIZON'",
        "phase = 'DRAINING'",
        "phase = 'QUIET'")) {
    if (-not $sources[$interSegmentModulePath].Contains($fragment)) {
        throw "The inter-segment state module is missing: $fragment"
    }
}

$emptyResetStart = $sources[$suitePath].IndexOf(
    'if ($currentCounts.Trim() -eq ''0|0'') {')
$emptyResetEnd = $sources[$suitePath].IndexOf(
    '$resetFacts = Get-PreviousBoundaryResetFacts', $emptyResetStart)
if ($emptyResetStart -lt 0 -or $emptyResetEnd -le $emptyResetStart) {
    throw 'Suite is missing the empty previous-run reset branch.'
}
$emptyResetBlock = $sources[$suitePath].Substring(
    $emptyResetStart, $emptyResetEnd - $emptyResetStart)
if ($emptyResetBlock.Contains('Assert-PostgresBoundaryBaseline')) {
    throw 'An empty previous-run reset must defer executable FREE baseline validation until /prepare.'
}
if (-not $emptyResetBlock.Contains('fixturePreparationRequired = $true')) {
    throw 'The empty previous-run reset evidence must declare that fixture preparation is required.'
}
$mainResetIndex = $sources[$suitePath].LastIndexOf('Invoke-PreviousBoundaryExactReset')
$mainPrepareIndex = $sources[$suitePath].IndexOf(
    '/internal/test/membership-payments/millisecond-boundary/prepare', $mainResetIndex)
$postPrepareBaselineIndex = $sources[$suitePath].IndexOf(
    'Assert-PostgresBoundaryBaseline', $mainPrepareIndex)
if ($mainResetIndex -lt 0 -or $mainPrepareIndex -le $mainResetIndex -or
        $postPrepareBaselineIndex -le $mainPrepareIndex) {
    throw 'Suite must run /prepare and then validate the executable PostgreSQL FREE baseline.'
}

if ($sources[$suitePath].Contains('Start-Sleep -Seconds $PrecheckSeconds') -or
        $sources[$suitePath].Contains('Invoke-FormalBusinessWarmup -OrderCount $WarmupOrderCount')) {
    throw 'The obsolete fixed empty precheck or one-time tiny warmup is still active.'
}

foreach ($fragment in @(
    'function Wait-RabbitMembershipQueueDrain',
    '[int] $RequiredZeroSamples = 3',
    'Start-Sleep -Milliseconds 500',
    'Wait-RabbitMembershipQueueDrain',
    'Save-RabbitSnapshot $rabbitAfter')) {
    if (-not $sources[$waveRunnerPath].Contains($fragment)) {
        throw "Wave orchestration is missing stable Rabbit drain evidence: $fragment"
    }
}
$rabbitDrainIndex = $sources[$waveRunnerPath].LastIndexOf('Wait-RabbitMembershipQueueDrain')
$rabbitSnapshotIndex = $sources[$waveRunnerPath].LastIndexOf('Save-RabbitSnapshot $rabbitAfter')
if ($rabbitDrainIndex -lt 0 -or $rabbitSnapshotIndex -lt 0 -or
        $rabbitDrainIndex -gt $rabbitSnapshotIndex) {
    throw 'Final Rabbit evidence must wait for stable drain before taking the strict snapshot.'
}
foreach ($fragment in @(
    "^[A-Za-z0-9_-]{1,128}$",
    "logs\membership-payment-state-machine.log",
    'Wait-TimingLogQuiescence',
    'Get-LatestStateMachineTimingSignature',
    'event=membership_payment_operation_completed ',
    'Assert-CompactTimingWarmup',
    'Fast compact HTTP timing line exceeds 512 bytes',
    'New-MembershipPaymentFocusedTimingReport.ps1',
    'RunId = $RunId',
    'ScenarioOrdersCsvPath = $allScenarioOrders',
    'OutputDirectory = $timingOutputDirectory',
    'membership-payment-focused-events.csv',
    'membership-payment-focused-operation-summary.csv',
    'membership-payment-focused-top-100.csv',
    'membership-payment-slow-failure-diagnostics.csv',
    'membership-payment-focused-report.json',
    'membership-payment-focused-report.md',
    "@('ORDER_CREATE', 'PAYMENT_ATTEMPT')",
    'uniqueOrderCount -ne $expectedRunOrders',
    'atLeast1000MsCount',
    'redisOrderWriteMaximumMs',
    'redisProviderWriteMaximumMs',
    'logVolumeTargetMet',
    'performance-verdict.json',
    '功能 PASS，但性能目标未达到。',
    'Copy-EvidenceFileIfNeeded',
    'slowlogLength',
    'redisWriteRejectedCount')) {
    if (-not $sources[$suitePath].Contains($fragment)) {
        throw "Suite is missing state-machine timing report orchestration: $fragment"
    }
}

$resumeBranchIndex = $sources[$suitePath].LastIndexOf('if ($SkipInitialGates) {')
$resumeValidationIndex = $sources[$suitePath].IndexOf(
    'Assert-ResumeBoundaryState', $resumeBranchIndex)
$normalResetIndex = $sources[$suitePath].IndexOf(
    'Invoke-PreviousBoundaryExactReset', $resumeBranchIndex)
if ($resumeBranchIndex -lt 0 -or $resumeValidationIndex -le $resumeBranchIndex -or
        $normalResetIndex -le $resumeValidationIndex) {
    throw 'Resumed execution must validate retained formal data before the normal reset/prepare branch.'
}

if ($sources[$suitePath].Contains(
        'Copy-Item -LiteralPath $path -Destination (Join-Path $outputRoot')) {
    throw 'Suite still copies generated timing evidence onto the same destination path.'
}
foreach ($fragment in @(
    "'ORDER_CREATE'",
    "'PAYMENT_ATTEMPT'",
    'unexpected fast success outside the two forced operations',
    'diagnosticEventCount',
    'MinimumCompletedAtEpochMs',
    'minimumCompletedAtEpochMs',
    'membership-payment-slow-failure-diagnostics.csv',
    'oldOrderCreateAnchorsMs',
    'redisOrderWriteMs',
    'redisProviderWriteMs',
    'otherRedisMs',
    'runLogBytes',
    'logVolumeTargetMiB',
    'logVolumeTargetMet')) {
    if (-not $sources[$timingReporterPath].Contains($fragment)) {
        throw "Focused timing reporter is missing its HTTP and diagnostic operation contract: $fragment"
    }
}
$reportInvocationIndex = $sources[$suitePath].IndexOf(
    'New-MembershipPaymentFocusedTimingReport.ps1')
$preserveIndex = $sources[$suitePath].IndexOf("preservePolicy = 'ALWAYS'")
if ($reportInvocationIndex -lt 0 -or $preserveIndex -lt 0 -or
    $reportInvocationIndex -ge $preserveIndex) {
    throw 'Focused timing reports must be generated before the permanent evidence-preservation marker.'
}
foreach ($fragment in @(
    '[string] $RunId = ''''',
    'MEMBERSHIP_PAYMENT_TIMING_RUN_ID',
    'MEMBERSHIP_PAYMENT_TIMING_INCLUDE_PUBLIC_ORDER_ID',
    'MEMBERSHIP_PAYMENT_TIMING_DETAIL_LOG_ENABLED',
    'MEMBERSHIP_PAYMENT_TIMING_SAMPLE_RATE',
    'MEMBERSHIP_PAYMENT_TIMING_FORCE_LOG_OPERATIONS',
    'ORDER_CREATE,PAYMENT_ATTEMPT',
    'MEMBERSHIP_PAYMENT_TIMING_LOG_PATH',
    'logs\membership-payment-state-machine.log')) {
    if (-not $sources[$applicationLauncherPath].Contains($fragment)) {
        throw "Application launcher is missing timing environment contract: $fragment"
    }
}
foreach ($fragment in @(
        'server-time-verdict.csv',
        'Merge-CsvFiles -Paths $serverVerdictPaths -Destination $finalTimestampEvidence',
        '-ExpectedRows $expectedRunOrders',
        'wave-functional-verification.json')) {
    if (-not $sources[$suitePath].Contains($fragment)) {
        throw "Suite is missing aggregate per-wave functional evidence: $fragment"
    }
}

Write-Output 'PASS: fixed 8x5,000 and 8x10,000 JMeter boundary contracts are complete.'
