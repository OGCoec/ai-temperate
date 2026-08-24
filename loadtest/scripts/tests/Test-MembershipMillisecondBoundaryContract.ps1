[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$inputPath = Join-Path $repositoryRoot 'loadtest\input\membership-millisecond-boundary-groups.csv'
$jmxPath = Join-Path $repositoryRoot 'loadtest\jmeter\membership-millisecond-boundary.jmx'
$driverPath = Join-Path $repositoryRoot 'loadtest\scripts\jmeter\membership-millisecond-boundary.groovy'
$waveRunnerPath = Join-Path $repositoryRoot 'loadtest\scripts\Invoke-MembershipMillisecondBoundaryWave.ps1'
$redisHelperPath = Join-Path $repositoryRoot 'loadtest\scripts\MembershipBoundaryRedis.ps1'
$jtlGatePath = Join-Path $repositoryRoot 'loadtest\scripts\Assert-MembershipJmeterResults.ps1'
$suitePath = Join-Path $repositoryRoot 'loadtest\scripts\Start-MembershipMillisecondBoundarySuite.ps1'
$waveSqlPath = Join-Path $repositoryRoot 'loadtest\sql\verify-membership-millisecond-boundary-wave.sql'
$finalSqlPath = Join-Path $repositoryRoot 'loadtest\sql\verify-membership-millisecond-boundary-final.sql'
$orderDdlPath = Join-Path $repositoryRoot 'sql\018_create_membership_order.sql'
$callbackDdlPath = Join-Path $repositoryRoot 'sql\019_create_membership_payment_callback.sql'
$timestampMigrationPath = Join-Path $repositoryRoot `
    'sql\migrations\033_set_membership_payment_timestamp_microsecond_precision.sql'
$allArtifacts = @(
    $inputPath,
    $jmxPath,
    $driverPath,
    $waveRunnerPath,
    $redisHelperPath,
    $jtlGatePath,
    $suitePath,
    $waveSqlPath,
    $finalSqlPath,
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
foreach ($wave in @('E-PRE', 'E-AFTER', 'H-PRE', 'H-AFTER')) {
    $waveRows = @($rows | Where-Object waveCode -eq $wave)
    if ($waveRows.Count -ne 2 -or (($waveRows | Measure-Object -Property userCount -Sum).Sum) -ne 10000) {
        throw "Wave $wave must contain exactly two groups and 10,000 users."
    }
}

$sources = @{}
foreach ($path in @(
    $jmxPath, $driverPath, $waveRunnerPath, $redisHelperPath, $suitePath,
    $waveSqlPath, $finalSqlPath)) {
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
if (-not $sources[$waveRunnerPath].Contains('[int] $CreationConcurrency = 4096')) {
    throw 'Boundary wave runner must default order creation concurrency to 4096.'
}
foreach ($fragment in @(
    "props.getProperty('CREATION_CONCURRENCY', '4096')",
    "props.getProperty('HTTP_CONCURRENCY', '4096')",
    'creationConcurrency > 4096',
    'httpConcurrency > 4096')) {
    if (-not $sources[$driverPath].Contains($fragment)) {
        throw "JMeter Groovy driver is missing the 4096 creation concurrency contract: $fragment"
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
    'CountDownLatch(5_000)',
    'dispatch_drift_micros',
    'Duration.between(order.targetAt, startedAt).toNanos() / 1_000L')) {
    if (-not $sources[$driverPath].Contains($fragment)) {
        throw "JMeter Groovy driver is missing microsecond or concurrency fragment: $fragment"
    }
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
    'closing_deadline_at IS NOT NULL',
    'closing_deadline_at IS DISTINCT FROM planned_hard_close_at',
    'server_target_drift_micros',
    'received_from_expires_micros',
    'received_from_hard_close_micros',
    'payment_started_at',
    'callback_paid_at',
    "THEN 'APPLIED'",
    "ELSE 'REFUND_REQUIRED'")) {
    if (-not $sources[$waveSqlPath].Contains($fragment)) {
        throw "Wave SQL is missing server-time verdict fragment: $fragment"
    }
}
if ($sources[$waveSqlPath].Contains('callback.received_at < payment_order.closing_deadline_at') `
    -or $sources[$waveSqlPath].Contains('callback.received_at >= payment_order.closing_deadline_at')) {
    throw 'Wave SQL must adjudicate against planned_hard_close_at, not nullable closing_deadline_at.'
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
    'ait:*:payment:*:v1:*',
    'ait:*:payment:*:v2:*',
    'quota_period_started_at IS NULL',
    'membership_expires_at IS NULL',
    'callbackReadySize',
    'callbackProcessingSize',
    'dirtyProcessingSize',
    '[int] $PrecheckSeconds = 120',
    '[int] $InterSegmentSeconds = 60',
    'Wait-InterSegmentStability',
    'verify-membership-millisecond-boundary-final.sql',
    'scenario-orders-all.csv',
    'callback-dispatch-all.csv',
    'final-timestamp-evidence.csv',
    'Merge-CsvFiles',
    'Write-ResetOrderManifest',
    '-InFile $resetManifest',
    '/reset')) {
    if (-not $sources[$suitePath].Contains($fragment)) {
        throw "Suite is missing fixed orchestration fragment: $fragment"
    }
}
foreach ($fragment in @(
    '40000',
    '1250',
    '2004',
    '8016',
    'COUNT(*) = 10',
    'COUNT(*) BETWEEN 2 AND 3',
    '__ALL_SCENARIO_ORDERS_CSV__',
    '__ALL_CALLBACK_DISPATCH_CSV__',
    '__FINAL_TIMESTAMP_EVIDENCE_CSV__',
    'callback.received_at < test.planned_hard_close_at',
    'dispatch_drift_micros',
    'received_from_expires_micros',
    'received_from_hard_close_micros',
    'payment_started_at',
    'callback_paid_at',
    'entitlement_resolution IS NULL',
    'membership_payment_callback',
    "SELECT 'PASS' AS verdict")) {
    if (-not $sources[$finalSqlPath].Contains($fragment)) {
        throw "Final SQL is missing aggregate invariant: $fragment"
    }
}
foreach ($forbidden in @(
    'Expected exactly 3,000 APPLIED boundary orders.',
    'Expected exactly 1,000 REFUND_REQUIRED boundary orders.')) {
    if ($sources[$finalSqlPath].Contains($forbidden)) {
        throw "Final SQL still contains a fixed resolution total: $forbidden"
    }
}

Write-Output 'PASS: 8x5,000 JMeter millisecond-boundary contract is complete.'
