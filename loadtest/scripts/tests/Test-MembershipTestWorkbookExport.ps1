[CmdletBinding()]
param(
    [string] $NodeExecutable =
        'C:\Users\damn\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe',
    [string] $NodeModulesPath =
        'C:\Users\damn\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$exporterPath = Join-Path $repositoryRoot `
    'loadtest\scripts\Export-MembershipTestWorkbook.ps1'
$builderPath = Join-Path $repositoryRoot `
    'loadtest\scripts\Build-MembershipTestWorkbook.mjs'
$sqlPath = Join-Path $repositoryRoot `
    'loadtest\sql\export-membership-test-workbook.sql'

foreach ($path in @($exporterPath, $builderPath, $sqlPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Membership workbook export artifact is missing: $path"
    }
}

$exporterSource = Get-Content -LiteralPath $exporterPath -Raw
$builderSource = Get-Content -LiteralPath $builderPath -Raw
$sqlSource = Get-Content -LiteralPath $sqlPath -Raw

foreach ($fragment in @(
        '[ValidateSet(''Snapshot'', ''RequireSuitePass'')]',
        '[int] $ExpectedRowsPerGroup',
        '[string[]] $ExpectedGroupCodes',
        '[int] $NodeMaxOldSpaceSizeMb = 8192',
        '[switch] $Overwrite',
        'previousScenarioOrdersCsvPaths',
        'scenario-orders.csv',
        'server-time-verdict.csv',
        '[IO.File]::Replace',
        '"--max-old-space-size=$NodeMaxOldSpaceSizeMb"',
        'Build-MembershipTestWorkbook.mjs',
        'export-membership-test-workbook.sql')) {
    if (-not $exporterSource.Contains($fragment)) {
        throw "Membership workbook exporter is missing contract: $fragment"
    }
}

if ($builderSource.Contains('const values = rows.map')) {
    throw 'Large worksheets must build only one bounded write chunk at a time.'
}
if ($builderSource.Contains('...row,')) {
    throw 'CSV row transforms must not duplicate every source row object.'
}
if (-not $exporterSource.Contains('--expose-gc')) {
    throw 'Membership workbook export must expose garbage collection.'
}
if (-not $builderSource.Contains('function collectGarbage')) {
    throw 'Membership workbook builder must collect unreachable CSV data between large worksheets.'
}
if ($builderSource.Contains('await Promise.all([')) {
    throw 'Large workbook CSV files must not be loaded concurrently.'
}
if ($builderSource.Contains('MembershipConsistencyDetailTable')) {
    throw 'Consistency detail must be validated in memory but not duplicated into the summary worksheet.'
}
foreach ($fragment in @(
        'async function verifyPersistedWorkbook',
        'const buildResult = await buildWorkbook(options);',
        'await verifyPersistedWorkbook(options, buildResult);')) {
    if (-not $builderSource.Contains($fragment)) {
        throw "Persisted workbook verification must run after the build workbook is released: $fragment"
    }
}

function Write-TestCsv {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [object[]] $Rows
    )

    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $Rows | Export-Csv -LiteralPath $Path -NoTypeInformation -Encoding utf8
}

function New-ScopeFixture {
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Name,
        [int] $RowsPerGroup = 2,
        [ValidateSet('PASS', 'FAIL')] [string] $SuiteStatus = 'FAIL'
    )

    $groupCodes = @(
        'E-P1', 'E-PR', 'E-A1', 'E-AR',
        'H-P1', 'H-PR', 'H-A1', 'H-AR'
    )
    $earlyGroups = @('E-P1', 'E-PR', 'E-A1', 'E-AR')
    $fixtureRoot = Join-Path $Root $Name
    $runRoot = Join-Path $fixtureRoot 'run'
    $previousRoot = Join-Path $fixtureRoot 'previous'
    New-Item -ItemType Directory -Force -Path $runRoot, $previousRoot | Out-Null
    $previousPaths = [Collections.Generic.List[string]]::new()

    for ($groupIndex = 0; $groupIndex -lt $groupCodes.Count; $groupIndex += 1) {
        $groupCode = $groupCodes[$groupIndex]
        $evidenceRoot = if ($groupCode -in $earlyGroups) {
            Join-Path $previousRoot $groupCode
        } else {
            Join-Path $runRoot $groupCode
        }
        New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
        $scenarioRows = [Collections.Generic.List[object]]::new()
        $serverRows = [Collections.Generic.List[object]]::new()
        for ($rowIndex = 0; $rowIndex -lt $RowsPerGroup; $rowIndex += 1) {
            $userId = $groupIndex * 100 + $rowIndex + 1
            $scenarioRows.Add([ordered]@{
                    run_id = "source-$groupCode"
                    wave_code = "wave-$groupCode"
                    group_code = $groupCode
                    trace_id = "trace-$groupCode-$rowIndex"
                    user_id = [string] $userId
                    target_tier = 'PLUS'
                    order_id = $userId.ToString('D22')
                    expires_at = '2026-08-27T20:00:00.000000Z'
                    hard_close_at = '2026-08-27T20:05:00.000000Z'
                    target_offset_millis = '1'
                    target_at = '2026-08-27T20:00:00.001000Z'
                })
            $serverRows.Add([ordered]@{
                    run_id = "source-$groupCode"
                    group_code = $groupCode
                    user_id = [string] $userId
                    order_id = $userId.ToString('D22')
                    failure = ''
                })
        }
        $scenarioPath = Join-Path $evidenceRoot 'scenario-orders.csv'
        Write-TestCsv -Path $scenarioPath -Rows @($scenarioRows)
        Write-TestCsv -Path (Join-Path $evidenceRoot 'server-time-verdict.csv') `
            -Rows @($serverRows)
        [ordered]@{
            verdict = 'PASS'
            completedAt = '2026-08-27T20:10:00.000000Z'
        } | ConvertTo-Json | Set-Content -LiteralPath (
            Join-Path $evidenceRoot 'verdict.json') -Encoding utf8
        if ($groupCode -in $earlyGroups) {
            $previousPaths.Add($scenarioPath)
        }
    }

    $manifestPath = Join-Path $runRoot 'run-manifest.json'
    [ordered]@{
        runId = "fixture-$Name"
        runScale = 'PERFORMANCE_TEST'
        sourceFingerprint = 'fixture-fingerprint'
        previousScenarioOrdersCsvPaths = @($previousPaths)
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    [ordered]@{
        verdict = $SuiteStatus
        runId = "fixture-$Name"
        originStage = if ($SuiteStatus -eq 'PASS') { $null } else { 'FORMAL_GOLDEN_REPORT' }
        primaryMessage = if ($SuiteStatus -eq 'PASS') { '' } else { 'fixture suite failure' }
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (
        Join-Path $runRoot 'verdict.json') -Encoding utf8
    [ordered]@{
        phase = 'MILLISECOND_BOUNDARY'
        state = $SuiteStatus
        wave = if ($SuiteStatus -eq 'PASS') { 'COMPLETE' } else { 'STOPPED' }
    } | ConvertTo-Json | Set-Content -LiteralPath (
        Join-Path $runRoot 'run-state.json') -Encoding utf8
    $outputFile = Join-Path $fixtureRoot 'input.xlsx'
    Set-Content -LiteralPath $outputFile -Value 'validation-only workbook placeholder' `
        -Encoding utf8
    return [pscustomobject]@{
        RunRoot = $runRoot
        ManifestPath = $manifestPath
        OutputFile = $outputFile
        RowsPerGroup = $RowsPerGroup
    }
}

function Invoke-ScopeValidation {
    param(
        [Parameter(Mandatory)] [object] $Fixture,
        [ValidateSet('Snapshot', 'RequireSuitePass')]
        [string] $CompletionPolicy = 'Snapshot'
    )

    return & $exporterPath `
        -RunManifestPath $Fixture.ManifestPath `
        -ExpectedRowsPerGroup $Fixture.RowsPerGroup `
        -OutputFile $Fixture.OutputFile `
        -CompletionPolicy $CompletionPolicy `
        -Overwrite `
        -ValidationOnly
}

function Assert-Throws {
    param(
        [Parameter(Mandatory)] [scriptblock] $Action,
        [Parameter(Mandatory)] [string] $Scenario
    )

    $threw = $false
    try {
        & $Action | Out-Null
    } catch {
        $threw = $true
    }
    if (-not $threw) {
        throw "Expected membership workbook validation to reject: $Scenario"
    }
}

foreach ($fragment in @(
        '\set ON_ERROR_STOP on',
        'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY',
        'CREATE TEMP TABLE membership_workbook_scope',
        '\gset',
        '\if :scope_count_ok',
        '\if :scope_uniqueness_ok',
        '__EXPECTED_TOTAL__',
        "FROM '__SCOPE_CSV__' CSV HEADER",
        "TO '__ORDERS_CSV__' CSV HEADER",
        "TO '__CALLBACKS_CSV__' CSV HEADER",
        "TO '__QUOTAS_CSV__' CSV HEADER",
        "TO '__ID_MAPPING_CSV__' CSV HEADER",
        "TO '__CONSISTENCY_CSV__' CSV HEADER",
        "TO '__METRICS_CSV__' CSV HEADER",
        'membership_order',
        'membership_payment_callback',
        'user_membership_quota',
        'missing_order_count',
        'missing_callback_count',
        'missing_quota_count',
        'public.hybrid_id_to_base64url',
        'encode(',
        'ROLLBACK')) {
    if (-not $sqlSource.Contains($fragment)) {
        throw "Membership workbook SQL is missing read-only contract: $fragment"
    }
}

foreach ($forbidden in @(
        "FROM :'scope_csv'",
        "TO :'orders_csv'",
        ':expected_total',
        'ON COMMIT DROP',
        "CAST('membership workbook scope row count mismatch' AS INTEGER)",
        "CAST('membership workbook scope contains duplicate users or orders'",
        'DELETE FROM membership_order',
        'DELETE FROM membership_payment_callback',
        'UPDATE user_membership_quota',
        'TRUNCATE',
        'CREATE INDEX',
        'ALTER TABLE')) {
    if ($sqlSource.Contains($forbidden)) {
        throw "Membership workbook SQL contains forbidden mutation: $forbidden"
    }
}

foreach ($fragment in @(
        "Replace('__SCOPE_CSV__'",
        "Replace('__ORDERS_CSV__'",
        "Replace('__EXPECTED_TOTAL__'",
        'export-membership-test-workbook-run.sql')) {
    if (-not $exporterSource.Contains($fragment)) {
        throw "Membership workbook exporter is missing psql path rendering: $fragment"
    }
}

foreach ($fragment in @(
        'encodeHybridHexToBase64Url',
        'encodePositiveLongToBase64Url',
        '^[A-Za-z0-9_-]{22}$',
        '^[A-Za-z0-9_-]{11}$',
        '测试总览',
        'ID映射',
        'membership_order',
        'membership_payment_callback',
        'user_membership_quota',
        'requireExactRowCount',
        'consistency_failure_count !== 0',
        '区段证据',
        '一致性校验',
        '字段说明')) {
    if (-not $builderSource.Contains($fragment)) {
        throw "Membership workbook builder is missing contract: $fragment"
    }
}

$evidenceCreationIndex = $builderSource.LastIndexOf(
    'createEvidenceSheet(workbook, metadata);')
$consistencyCreationIndex = $builderSource.LastIndexOf(
    'createConsistencySheet(workbook, metrics, metadata);')
$summaryFormulaFinalizationIndex = $builderSource.LastIndexOf(
    'finalizeSummaryFormulas(summarySheet, metadata);')
if ($evidenceCreationIndex -lt 0 -or
        $consistencyCreationIndex -lt 0 -or
        $summaryFormulaFinalizationIndex -le $evidenceCreationIndex -or
        $summaryFormulaFinalizationIndex -le $consistencyCreationIndex) {
    throw 'Cross-sheet summary formulas must be finalized after all referenced worksheets exist.'
}

if (-not (Test-Path -LiteralPath $NodeExecutable -PathType Leaf)) {
    throw "Bundled Node.js executable is unavailable: $NodeExecutable"
}
if (-not (Test-Path -LiteralPath $NodeModulesPath -PathType Container)) {
    throw "Bundled Node.js modules are unavailable: $NodeModulesPath"
}

$tempDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('membership-workbook-export-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDirectory | Out-Null

try {
    $snapshotFixture = New-ScopeFixture -Root $tempDirectory -Name 'snapshot'
    $snapshotResult = Invoke-ScopeValidation -Fixture $snapshotFixture `
        -CompletionPolicy Snapshot
    if (-not $snapshotResult.ScopeValidated -or $snapshotResult.FullyTested -or
            $snapshotResult.ExpectedRows -ne 16) {
        throw 'Snapshot policy did not preserve the Suite FAIL conclusion.'
    }
    Assert-Throws -Scenario 'Suite FAIL with RequireSuitePass' -Action {
        Invoke-ScopeValidation -Fixture $snapshotFixture `
            -CompletionPolicy RequireSuitePass
    }

    $passFixture = New-ScopeFixture -Root $tempDirectory -Name 'pass' -SuiteStatus PASS
    $passResult = Invoke-ScopeValidation -Fixture $passFixture `
        -CompletionPolicy RequireSuitePass
    if (-not $passResult.ScopeValidated -or -not $passResult.FullyTested) {
        throw 'RequireSuitePass did not accept a complete PASS fixture.'
    }

    $missingGroupFixture = New-ScopeFixture -Root $tempDirectory -Name 'missing-group'
    Remove-Item -LiteralPath (Join-Path $missingGroupFixture.RunRoot `
        'H-AR\scenario-orders.csv') -Force
    Assert-Throws -Scenario 'missing group' -Action {
        Invoke-ScopeValidation -Fixture $missingGroupFixture
    }

    $shortGroupFixture = New-ScopeFixture -Root $tempDirectory -Name 'short-group'
    $shortPath = Join-Path $shortGroupFixture.RunRoot 'H-P1\scenario-orders.csv'
    $shortRows = @(Import-Csv -LiteralPath $shortPath | Select-Object -First 1)
    Write-TestCsv -Path $shortPath -Rows $shortRows
    Assert-Throws -Scenario 'group below expected row count' -Action {
        Invoke-ScopeValidation -Fixture $shortGroupFixture
    }

    $longGroupFixture = New-ScopeFixture -Root $tempDirectory -Name 'long-group'
    $longPath = Join-Path $longGroupFixture.RunRoot 'H-PR\scenario-orders.csv'
    $longRows = @(Import-Csv -LiteralPath $longPath)
    $extraRow = $longRows[-1].PSObject.Copy()
    $extraRow.user_id = '999999'
    $extraRow.order_id = '9999999999999999999999'
    $longRows += $extraRow
    Write-TestCsv -Path $longPath -Rows $longRows
    Assert-Throws -Scenario 'group above expected row count' -Action {
        Invoke-ScopeValidation -Fixture $longGroupFixture
    }

    $duplicateFixture = New-ScopeFixture -Root $tempDirectory -Name 'duplicate'
    $duplicatePath = Join-Path $duplicateFixture.RunRoot 'H-A1\scenario-orders.csv'
    $duplicateRows = @(Import-Csv -LiteralPath $duplicatePath)
    $duplicateRows[1].order_id = $duplicateRows[0].order_id
    Write-TestCsv -Path $duplicatePath -Rows $duplicateRows
    Assert-Throws -Scenario 'duplicate order inside a group' -Action {
        Invoke-ScopeValidation -Fixture $duplicateFixture
    }

    $crossGroupFixture = New-ScopeFixture -Root $tempDirectory -Name 'cross-group'
    $sourcePath = Join-Path $crossGroupFixture.RunRoot 'H-A1\scenario-orders.csv'
    $targetPath = Join-Path $crossGroupFixture.RunRoot 'H-AR\scenario-orders.csv'
    $sourceRows = @(Import-Csv -LiteralPath $sourcePath)
    $targetRows = @(Import-Csv -LiteralPath $targetPath)
    $targetRows[0].user_id = $sourceRows[0].user_id
    Write-TestCsv -Path $targetPath -Rows $targetRows
    Assert-Throws -Scenario 'duplicate user across groups' -Action {
        Invoke-ScopeValidation -Fixture $crossGroupFixture
    }

    $conflictFixture = New-ScopeFixture -Root $tempDirectory -Name 'conflict'
    $conflictManifest = Get-Content -LiteralPath $conflictFixture.ManifestPath -Raw |
        ConvertFrom-Json
    $previousConflictPath = @($conflictManifest.previousScenarioOrdersCsvPaths |
            Where-Object { $_ -like '*E-P1*' })[0]
    $currentConflictPath = Join-Path $conflictFixture.RunRoot 'E-P1\scenario-orders.csv'
    $conflictRows = @(Import-Csv -LiteralPath $previousConflictPath)
    $conflictRows[0].order_id = '8888888888888888888888'
    Write-TestCsv -Path $currentConflictPath -Rows $conflictRows
    Assert-Throws -Scenario 'conflicting sources for one group' -Action {
        Invoke-ScopeValidation -Fixture $conflictFixture
    }

    $lockedFixture = New-ScopeFixture -Root $tempDirectory -Name 'locked'
    $lockedStream = [IO.File]::Open(
        $lockedFixture.OutputFile,
        [IO.FileMode]::Open,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None)
    try {
        Assert-Throws -Scenario 'locked output workbook' -Action {
            Invoke-ScopeValidation -Fixture $lockedFixture
        }
    } finally {
        $lockedStream.Dispose()
    }

    $nodeModulesJunction = Join-Path $tempDirectory 'node_modules'
    New-Item -ItemType Junction -Path $nodeModulesJunction `
        -Target $NodeModulesPath | Out-Null
    $copiedBuilder = Join-Path $tempDirectory 'Build-MembershipTestWorkbook.mjs'
    Copy-Item -LiteralPath $builderPath -Destination $copiedBuilder

    $codecTestPath = Join-Path $tempDirectory 'codec-test.mjs'
    @'
import assert from "node:assert/strict";
import {
  encodeHybridHexToBase64Url,
  encodePositiveLongToBase64Url,
} from "./Build-MembershipTestWorkbook.mjs";

assert.equal(
  encodeHybridHexToBase64Url("00000000000000000000000000000000"),
  "AAAAAAAAAAAAAAAAAAAAAA",
);
assert.equal(encodePositiveLongToBase64Url("1"), "AAAAAAAAAAE");
assert.match(
  encodePositiveLongToBase64Url("70000000000020000"),
  /^[A-Za-z0-9_-]{11}$/,
);
assert.throws(() => encodeHybridHexToBase64Url("00"));
assert.throws(() => encodeHybridHexToBase64Url("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));
assert.throws(() => encodePositiveLongToBase64Url("0"));
assert.throws(() => encodePositiveLongToBase64Url("-1"));
assert.throws(() => encodePositiveLongToBase64Url("01"));
assert.throws(() => encodePositiveLongToBase64Url("1.0"));
assert.throws(() => encodePositiveLongToBase64Url("9223372036854775808"));
console.log("PASS: membership workbook Base64URL codecs are canonical.");
'@ | Set-Content -LiteralPath $codecTestPath -Encoding UTF8

    & $NodeExecutable $codecTestPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Membership workbook Base64URL codec test failed.'
    }
} finally {
    Remove-Item -LiteralPath $tempDirectory -Recurse -Force `
        -ErrorAction SilentlyContinue
}

Write-Output 'PASS: membership test workbook export contracts are complete.'
