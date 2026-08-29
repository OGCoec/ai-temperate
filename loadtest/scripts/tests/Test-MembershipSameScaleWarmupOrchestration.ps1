[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$suitePath = Join-Path $repositoryRoot 'loadtest\scripts\Start-MembershipMillisecondBoundarySuite.ps1'
$wavePath = Join-Path $repositoryRoot 'loadtest\scripts\Invoke-MembershipMillisecondBoundaryWave.ps1'
$driverPath = Join-Path $repositoryRoot 'loadtest\scripts\jmeter\membership-millisecond-boundary.groovy'
$redisPath = Join-Path $repositoryRoot 'loadtest\scripts\MembershipBoundaryRedis.ps1'
$reportPath = Join-Path $repositoryRoot 'loadtest\scripts\New-MembershipWarmupStabilityReport.ps1'

foreach ($path in @($suitePath, $wavePath, $driverPath, $redisPath, $reportPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Same-scale warmup contract artifact is missing: $path"
    }
}

$suite = Get-Content -Raw -LiteralPath $suitePath
$wave = Get-Content -Raw -LiteralPath $wavePath
$driver = Get-Content -Raw -LiteralPath $driverPath
$redis = Get-Content -Raw -LiteralPath $redisPath

$waveFragments = @(
    "[ValidateSet('WARMUP', 'FORMAL')]",
    '[string] $ExecutionPhase',
    '[int] $WarmupAttempt',
    'attempt-$WarmupAttempt',
    'executionPhase = $ExecutionPhase',
    'warmupAttempt = $WarmupAttempt',
    '"-JEXECUTION_PHASE=$ExecutionPhase"',
    "`$teamProbeCount = if (`$ExecutionPhase -eq 'FORMAL') { 25 } else { 0 }"
)
foreach ($fragment in $waveFragments) {
    if (-not $wave.Contains($fragment)) {
        throw "Boundary wave is missing same-scale warmup phase contract: $fragment"
    }
}

$driverFragments = @(
    "String executionPhase = props.getProperty('EXECUTION_PHASE', 'FORMAL')",
    "if (!(executionPhase in ['WARMUP', 'FORMAL']))",
    "if (executionPhase == 'FORMAL')",
    "int expectedTeamProbeCount = executionPhase == 'FORMAL' ? 25 : 0"
)
foreach ($fragment in $driverFragments) {
    if (-not $driver.Contains($fragment)) {
        throw "JMeter driver is missing phase-isolated TEAM probe behavior: $fragment"
    }
}

$suiteFragments = @(
    '$expectedWarmupOrders = $expectedSegmentOrders',
    "`$allGroups = @('E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR')",
    'for ($warmupAttempt = 1; $warmupAttempt -le 2; $warmupAttempt += 1)',
    '$attemptResults = [Collections.Generic.List[object]]::new()',
    'if ($warmupAttempt -eq 2)',
    'warmup2ContractPerformancePassed = [bool]$verdict.contractPerformancePassed',
    "-ExecutionPhase 'WARMUP'",
    '-WarmupAttempt $Attempt',
    "`$null = & (Join-Path `$PSScriptRoot 'Invoke-MembershipMillisecondBoundaryWave.ps1')",
    "`$null = & (Join-Path `$PSScriptRoot 'New-MembershipPaymentFocusedTimingReport.ps1')",
    "`$null = & (Join-Path `$PSScriptRoot 'New-MembershipWarmupStabilityReport.ps1')",
    "[string] `$TimingLogRunId = ''",
    'if ([string]::IsNullOrWhiteSpace($TimingLogRunId)) { $TimingLogRunId = $RunId }',
    '-LogRunId $TimingLogRunId',
    'function Get-HttpEvidenceRunId',
    '$candidate = "$TimingLogRunId-warmup-$GroupCode-a$Attempt"',
    '-HttpEvidenceRunId $warmupHttpEvidenceRunId',
    '-HttpLogRunId $warmupHttpEvidenceRunId',
    '-HttpEvidenceRunId $TimingLogRunId',
    '-HttpLogRunId $TimingLogRunId',
    '-AllowEventsOutsideScenarioManifest',
    '-AllowEventsOutsideManifest',
    'TEST_INVALID_WARMUP_EVIDENCE',
    'if (-not [bool]$stabilityVerdict.functionalPassed)',
    'New-MembershipWarmupStabilityReport.ps1',
    'segment-warmup-reset',
    'Remove-MembershipBoundaryRedisExactWarmupArtifacts',
    "-MinimumQps 900",
    '$maximumFormalWallClockSeconds',
    'warmup-to-formal-gap.json',
    'maximumGapSeconds = 10',
    'applicationPid = $segmentProcessEvidence.applicationPid',
    'postgresPid = $script:postgresStabilityBaseline.listenerPid',
    'samplerPid = $segmentProcessEvidence.samplerPid',
    'suitePid = $PID',
    '$formalFirstRequestDeadline = $warmupResult.cleanupCompletedAt.AddSeconds(10)',
    '-FormalFirstRequestDeadlineEpochMillis $formalFirstRequestDeadline.ToUnixTimeMilliseconds()',
    '$segmentPerformanceFailures.Add(',
    "-ExecutionPhase 'FORMAL'",
    '-WarmupAttempt 0'
)
foreach ($fragment in $suiteFragments) {
    if (-not $suite.Contains($fragment)) {
        throw "Boundary Suite is missing per-segment same-scale warmup contract: $fragment"
    }
}
if ($suite.Contains('Warmup attempt 2 did not satisfy the full-segment contract gate')) {
    throw 'Boundary Suite must allow a performance-failed warmup 2 to advance to the formal segment.'
}
if ($suite.Contains('Invoke-FormalBusinessWarmup -OrderCount $WarmupOrderCount')) {
    throw 'The obsolete one-time tiny warmup is still invoked.'
}
if ($suite -match "if \(\[string\]\`$verdict\.verdict -eq 'PASS'\) \{\s*return") {
    throw 'Warmup attempt 1 can still return early and skip the required second warmup.'
}
if ($suite -match 'WarmupStartAttempt|warmupStartAttempt|attempt 2 resume') {
    throw 'The Suite still allows cross-Run warmup attempt 2 reuse.'
}

foreach ($fragment in @(
        '[long] $FormalFirstRequestDeadlineEpochMillis = 0',
        '[string] $HttpEvidenceRunId',
        'FORMAL_START_DEADLINE_EXPIRED',
        '"-JHTTP_EVIDENCE_RUN_ID=$HttpEvidenceRunId"',
        '"-JFORMAL_FIRST_REQUEST_DEADLINE_EPOCH_MILLIS=$FormalFirstRequestDeadlineEpochMillis"')) {
    if (-not $wave.Contains($fragment)) {
        throw "Boundary wave is missing the pre-request ten-second deadline contract: $fragment"
    }
}
if ($wave.Contains('$cleanupInstant')) {
    throw 'Boundary wave must not reference the Suite-owned cleanup instant after a successful warmup.'
}
foreach ($fragment in @(
        "String httpEvidenceRunId = props.getProperty('HTTP_EVIDENCE_RUN_ID', runId)",
        "props.getProperty('FORMAL_FIRST_REQUEST_DEADLINE_EPOCH_MILLIS', '0')",
        'AtomicLong formalFirstCreateEpochMillis = new AtomicLong(0L)',
        'formalFirstCreateEpochMillis.compareAndSet(0L, createStartedEpochMillis)',
        'FORMAL_START_DEADLINE_EXPIRED')) {
    if (-not $driver.Contains($fragment)) {
        throw "JMeter driver is missing the pre-request ten-second deadline contract: $fragment"
    }
}
if (-not $driver.Contains("'X-Loadtest-Run-Id': httpEvidenceRunId")) {
    throw 'JMeter driver still publishes the business Run ID as the same-PID HTTP evidence identity.'
}

$warmupReport = Get-Content -Raw -LiteralPath $reportPath
foreach ($fragment in @(
        '[string] $HttpLogRunId',
        'if ([string]::IsNullOrWhiteSpace($HttpLogRunId)) { $HttpLogRunId = $RunId }',
        '$eventRunId -ne $HttpLogRunId',
        '$lastTwo = @(',
        "'WARMUP_HTTP_EVIDENCE_INCOMPLETE'",
        'failureCode = $failureCode')) {
    if (-not $warmupReport.Contains($fragment)) {
        throw "Warmup reporter is missing reused-application or empty-evidence protection: $fragment"
    }
}

foreach ($fragment in @(
        '$failureRecord = $_',
        'failureClass = $failureVerdict',
        'failureCode = $failureCode',
        "originComponent = 'MILLISECOND_BOUNDARY_SUITE'",
        'originStage = $script:currentOriginStage',
        'scriptLineNumber = $failureRecord.InvocationInfo.ScriptLineNumber',
        'scriptStackTrace = $failureRecord.ScriptStackTrace')) {
    if (-not $suite.Contains($fragment)) {
        throw "Boundary Suite is missing structured original-failure evidence: $fragment"
    }
}

$redisFragments = @(
    'function Remove-MembershipBoundaryRedisExactWarmupArtifacts',
    "'ait:{0}:payment:membership-order:v{1}:snapshot:{2}'",
    "'ait:{0}:payment:callback:v{1}:ready:all'",
    "'ait:{0}:payment:order-persist:v{1}:dirty:all'"
)
foreach ($fragment in $redisFragments) {
    if (-not $redis.Contains($fragment)) {
        throw "Redis helper is missing exact non-SCAN warmup cleanup: $fragment"
    }
}

Write-Output 'PASS'
