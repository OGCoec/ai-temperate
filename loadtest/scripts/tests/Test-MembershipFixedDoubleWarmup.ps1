[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$suitePath = Join-Path $repositoryRoot 'loadtest\scripts\Start-MembershipMillisecondBoundarySuite.ps1'
$warmupReportPath = Join-Path $repositoryRoot 'loadtest\scripts\New-MembershipWarmupStabilityReport.ps1'
$masterPath = Join-Path $repositoryRoot 'loadtest\scripts\Start-MembershipOrderCreateOptimizationRetest.ps1'
$schedulerPath = Join-Path $repositoryRoot 'loadtest\scripts\Start-MembershipSchedulerIndexHikariRetest.ps1'
$suiteChildPath = Join-Path $repositoryRoot 'loadtest\scripts\Invoke-MembershipMillisecondBoundarySuiteChild.ps1'
$focusedReportPath = Join-Path $repositoryRoot 'loadtest\scripts\New-MembershipPaymentFocusedTimingReport.ps1'
foreach ($path in @($suitePath, $warmupReportPath, $masterPath, $schedulerPath, $suiteChildPath, $focusedReportPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Fixed double-warmup contract artifact is missing: $path"
    }
}

$suite = Get-Content -Raw -LiteralPath $suitePath
$warmupReport = Get-Content -Raw -LiteralPath $warmupReportPath
$master = Get-Content -Raw -LiteralPath $masterPath
$scheduler = Get-Content -Raw -LiteralPath $schedulerPath
$suiteChild = Get-Content -Raw -LiteralPath $suiteChildPath
$focusedReport = Get-Content -Raw -LiteralPath $focusedReportPath

foreach ($fragment in @(
        'for ($warmupAttempt = 1; $warmupAttempt -le 2; $warmupAttempt += 1)',
        '$attemptResults = [Collections.Generic.List[object]]::new()',
        'if (-not [bool]$verdict.functionalPassed)',
        'if ($warmupAttempt -eq 2)',
        'warmup2ContractPerformancePassed = [bool]$verdict.contractPerformancePassed',
        'attempt = 2')) {
    if (-not $suite.Contains($fragment)) {
        throw "Suite is missing the exact two-warmup decision contract: $fragment"
    }
}
if ($suite.Contains('Warmup attempt 2 did not satisfy the full-segment contract gate')) {
    throw 'Suite must record a warmup-2 performance failure without preventing the formal segment.'
}

foreach ($fragment in @(
        '[switch] $StopAfterWarmupSequence',
        "Join-Path `$outputRoot 'warmup-only-completion.json'",
        "Save-State 'PASS' 'WARMUP_ONLY_COMPLETE'")) {
    if (-not $suite.Contains($fragment)) {
        throw "Suite is missing the exact two-attempt warmup-only stop contract: $fragment"
    }
}
foreach ($fragment in @(
        '[switch] $StopAfterWarmupSequence',
        'stopAfterWarmupSequence = [bool]$StopAfterWarmupSequence',
        "Join-Path `$runRoot 'warmup-only-completion.json'")) {
    if (-not $scheduler.Contains($fragment)) {
        throw "Scheduler is missing the exact two-attempt warmup-only handoff contract: $fragment"
    }
}
foreach ($fragment in @('$arguments.StopAfterWarmupSequence = $true')) {
    if (-not $suiteChild.Contains($fragment)) {
        throw "Suite child is missing the exact two-attempt warmup-only bridge contract: $fragment"
    }
}
foreach ($source in @($suite, $scheduler, $suiteChild)) {
    if ($source -match 'WarmupStartAttempt|warmupStartAttempt|attempt 2 resume') {
        throw 'Cross-Run attempt-2 resume is still exposed; every interrupted segment must restart at warmup attempt 1.'
    }
}
if ($suite -match 'warmupAttempt\s*-le\s*3' -or
        $suite -match "if \(\[string\]\`$verdict\.verdict -eq 'PASS'\) \{\s*return") {
    throw 'Suite still permits a third warmup or an attempt-1 performance early return.'
}

foreach ($fragment in @(
        'function Get-PostgresLogPaths',
        'C:\Users\damn\Desktop\postgresql\postgresql-5431.log',
        'monitoredLogPaths = @($logOffsets.Keys | Sort-Object)',
        'windowsProcessHost = $identity.windowsProcessHost',
        '$rawWatchErrors = Get-Content -Raw -LiteralPath $watchErrorPath',
        "if (`$null -eq `$rawWatchErrors) { '' } else { `$rawWatchErrors.Trim() }",
        "Get-Service -Name 'postgresql-x64-18-5431'",
        "Join-Path `$outputRoot 'previous-exact-reset-receipt.json'",
        'deletedOrderCount = $resetFacts.manifestOrderCount',
        'TEST_INVALID_POSTGRES: PostgreSQL postmaster PID or start time changed during the run.')) {
    if (-not $suite.Contains($fragment)) {
        throw "Suite is missing the Windows PostgreSQL host/log stability contract: $fragment"
    }
}

foreach ($fragment in @(
        '$fullLastCompleted - $fullFirstReceived',
        '$successfulHttp.Count * 1000000D / $fullWallMicros',
        '$expectedCount -eq 5000) { 5.556D } else { 11.112D }',
        '$fullQpsRaw -ge 900D',
        '$fullQpsRaw -gt 1000D',
        '$scenarioUsers = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)',
        'lastTwoWindowsDiagnosticOnly = $true',
        'lastTwoWindowsDiagnosticPassed = $stableTailDiagnostic',
        '$completeEvidence -and $contractPerformancePassed')) {
    if (-not $warmupReport.Contains($fragment)) {
        throw "Warmup reporter is missing a full-segment or diagnostic-only contract: $fragment"
    }
}
if ($warmupReport -match '\$completeEvidence\s+-and\s+\$stableTail\b') {
    throw 'The old last-two-window hard gate is still part of the warmup verdict.'
}

foreach ($fragment in @(
        "`$allGroups = @('E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR')",
        '$expectedSegmentOrders = if ($RunScale -eq ''PERFORMANCE_40K'') { 5000 } else { 10000 }',
        '$expectedRetainedFormalCount = $fixedGroupIndex * $expectedSegmentOrders',
        '$formalFirstRequestDeadline = $warmupResult.cleanupCompletedAt.AddSeconds(10)',
        '-FormalFirstRequestDeadlineEpochMillis $formalFirstRequestDeadline.ToUnixTimeMilliseconds()')) {
    if (-not $suite.Contains($fragment)) {
        throw "Suite is missing the fixed eight-segment double-warmup contract: $fragment"
    }
}
foreach ($fragment in @(
        "-Stage 'E-P1_CANARY_5K'",
        "-Stage 'PERFORMANCE_40K'",
        "-Stage 'CAPACITY_80K'",
        '-ExpectedFormalSegmentCount 1',
        '-ExpectedFormalSegmentCount 8',
        'existingPostgresStabilityGatePath = $postgresStabilityGatePath',
        'postgresStabilitySeconds = if ([string]::IsNullOrWhiteSpace($postgresStabilityGatePath))',
        'previousScenarioOrdersCsvPath = @($previousScenarioPaths)',
        "`$Stage -in @('PERFORMANCE_40K','CAPACITY_80K')",
        "if (`$Stage -eq 'PERFORMANCE_40K') { 5000 } else { 40000 }")) {
    if (-not $master.Contains($fragment)) {
        throw "Master is missing the Canary/40K/80K handoff contract: $fragment"
    }
}
foreach ($fragment in @(
        '[long] $FormalFirstRequestDeadlineEpochMillis = 0',
        'FORMAL_START_DEADLINE_EXPIRED',
        '"-JFORMAL_FIRST_REQUEST_DEADLINE_EPOCH_MILLIS=$FormalFirstRequestDeadlineEpochMillis"')) {
    if (-not (Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot `
                'loadtest\scripts\Invoke-MembershipMillisecondBoundaryWave.ps1')).Contains($fragment)) {
        throw "Wave runner is missing the pre-request ten-second deadline contract: $fragment"
    }
}
$boundaryDriver = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot `
    'loadtest\scripts\jmeter\membership-millisecond-boundary.groovy')
foreach ($fragment in @(
        "props.getProperty('FORMAL_FIRST_REQUEST_DEADLINE_EPOCH_MILLIS', '0')",
        'FORMAL_START_DEADLINE_EXPIRED')) {
    if (-not $boundaryDriver.Contains($fragment)) {
        throw "JMeter driver is missing the pre-request ten-second deadline contract: $fragment"
    }
}
if (-not $scheduler.Contains('timingLogRunId = [string]$application.runId') -or
        -not $suite.Contains('-LogRunId $TimingLogRunId') -or
        -not $suite.Contains('-HttpEvidenceRunId $warmupHttpEvidenceRunId') -or
        -not $suite.Contains('-HttpLogRunId $warmupHttpEvidenceRunId') -or
        -not $boundaryDriver.Contains("'X-Loadtest-Run-Id': httpEvidenceRunId")) {
    throw 'Same-PID reuse does not preserve the original application timing-log identity.'
}
foreach ($fragment in @(
        'schemaVersion = 1',
        'failureClass = $failureStatus',
        'failureCode = $failureCode',
        'primaryMessage = $orchestratorFailure',
        'originComponent = $originComponent',
        'originStage = $originStage',
        'diagnosticStderrPath = $suiteStderrPath')) {
    if (-not $scheduler.Contains($fragment)) {
        throw "Scheduler is missing structured child-failure propagation: $fragment"
    }
}
foreach ($fragment in @(
        "Join-Path `$childRoot 'orchestrator-failure.json'",
        'primaryMessage',
        'failureCode')) {
    if (-not $master.Contains($fragment)) {
        throw "Master is missing structured Scheduler-failure consumption: $fragment"
    }
}
if (-not $suite.Contains('AllowEventsOutsideScenarioManifest = $true') -or
        -not $focusedReport.Contains('[switch] $AllowEventsOutsideScenarioManifest') -or
        -not $focusedReport.Contains('outsideScenarioFocusedEventCount')) {
    throw 'The final shared application log report cannot exclude and account for independent warmup events.'
}
if ($master.IndexOf("-Stage 'E-P1_CANARY_5K'") -gt
        $master.IndexOf("-Stage 'PERFORMANCE_40K'") -or
        $master.IndexOf("-Stage 'PERFORMANCE_40K'") -gt
        $master.IndexOf("-Stage 'CAPACITY_80K'")) {
    throw 'Master stage order is not Canary -> 40K -> 80K.'
}

foreach ($fragment in @(
        "{ 'PASS' } else { 'PASS_WITH_WARNINGS' }",
        'functionalPassed = $true',
        'performancePassed = $httpVerdict.verdict',
        'currentGroupCode = $progress.groupCode',
        'warmupAttempt = $progress.warmupAttempt')) {
    if (-not $scheduler.Contains($fragment)) {
        throw "Scheduler is missing Canary continuation or heartbeat evidence: $fragment"
    }
}

Write-Output 'PASS'
