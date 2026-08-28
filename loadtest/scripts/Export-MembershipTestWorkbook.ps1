[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $RunManifestPath,

    [Parameter(Mandatory)]
    [ValidateRange(1, 1000000)]
    [int] $ExpectedRowsPerGroup,

    [string[]] $ExpectedGroupCodes = @(
        'E-P1', 'E-PR', 'E-A1', 'E-AR',
        'H-P1', 'H-PR', 'H-A1', 'H-AR'
    ),

    [Parameter(Mandatory)]
    [string] $OutputFile,

    [string] $PostgresUrl = 'postgresql://postgres@127.0.0.1:5431/ai_temperate',

    [ValidateSet('Snapshot', 'RequireSuitePass')]
    [string] $CompletionPolicy = 'RequireSuitePass',

    [switch] $Overwrite,

    [Parameter(DontShow)]
    [switch] $ValidationOnly,

    [string] $NodeExecutable =
        'C:\Users\damn\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe',

    [string] $NodeModulesPath =
        'C:\Users\damn\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules',

    [ValidateRange(4096, 32768)]
    [int] $NodeMaxOldSpaceSizeMb = 8192
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$fixedGroupCodes = @(
    'E-P1', 'E-PR', 'E-A1', 'E-AR',
    'H-P1', 'H-PR', 'H-A1', 'H-AR'
)
$earlyGroupCodes = @('E-P1', 'E-PR', 'E-A1', 'E-AR')
$lateGroupCodes = @('H-P1', 'H-PR', 'H-A1', 'H-AR')

function Get-RequiredTextProperty {
    param(
        [Parameter(Mandatory)] [object] $InputObject,
        [Parameter(Mandatory)] [string] $PropertyName,
        [Parameter(Mandatory)] [string] $SourceDescription
    )

    $property = $InputObject.PSObject.Properties[$PropertyName]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string] $property.Value)) {
        throw "Required property '$PropertyName' is missing in $SourceDescription."
    }
    return ([string] $property.Value).Trim()
}

function Read-JsonFile {
    param([Parameter(Mandatory)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required JSON evidence is missing: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Write-Utf8Json {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [object] $Value
    )

    $json = $Value | ConvertTo-Json -Depth 12
    [IO.File]::WriteAllText(
        $Path,
        $json + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
}

function Convert-ToPsqlPath {
    param([Parameter(Mandatory)] [string] $Path)

    return [IO.Path]::GetFullPath($Path).Replace('\', '/')
}

function Assert-TargetWorkbookUnlocked {
    param([Parameter(Mandatory)] [string] $Path)

    try {
        $stream = [IO.File]::Open(
            $Path,
            [IO.FileMode]::Open,
            [IO.FileAccess]::ReadWrite,
            [IO.FileShare]::None)
        $stream.Dispose()
    } catch {
        throw "Output workbook is unavailable or locked; the original file was not changed: $Path"
    }
}

function Read-ScenarioEvidence {
    param([Parameter(Mandatory)] [string] $Path)

    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    $rows = @(Import-Csv -LiteralPath $resolvedPath)
    if ($rows.Count -eq 0) {
        throw "Scenario evidence is empty: $resolvedPath"
    }
    $groups = @($rows | ForEach-Object {
            Get-RequiredTextProperty -InputObject $_ -PropertyName 'group_code' `
                -SourceDescription $resolvedPath
        } | Sort-Object -Unique)
    if ($groups.Count -ne 1) {
        throw "Scenario evidence must contain exactly one group_code: $resolvedPath"
    }
    return [pscustomobject]@{
        Path = $resolvedPath
        GroupCode = $groups[0]
        Rows = $rows
        Sha256 = (Get-FileHash -LiteralPath $resolvedPath -Algorithm SHA256).Hash
    }
}

function Assert-NoConflictingSource {
    param(
        [Parameter(Mandatory)] [object] $Selected,
        [object[]] $Alternatives = @()
    )

    foreach ($alternative in $Alternatives) {
        if ($alternative.Path -ceq $Selected.Path) {
            continue
        }
        if ($alternative.Sha256 -cne $Selected.Sha256) {
            throw "Conflicting scenario-orders.csv sources exist for group $($Selected.GroupCode): " +
                "$($Selected.Path) and $($alternative.Path)"
        }
    }
}

function Get-ArtifactMarkerPath {
    $candidate = Join-Path $env:USERPROFILE (
        '.cache\codex-runtimes\codex-primary-runtime\plugins\openai-primary-runtime\' +
        'plugins\spreadsheets\skills\spreadsheets\container_tools\' +
        'mark_artifact_operation_started.mjs')
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Spreadsheet artifact operation marker is unavailable: $candidate"
    }
    return $candidate
}

if ($ExpectedGroupCodes.Count -ne $fixedGroupCodes.Count -or
        ($ExpectedGroupCodes -join ',') -cne ($fixedGroupCodes -join ',')) {
    throw 'ExpectedGroupCodes must contain the immutable eight groups in the documented order.'
}
if ([string]::IsNullOrWhiteSpace($PostgresUrl)) {
    $PostgresUrl = if (-not [string]::IsNullOrWhiteSpace(
            $env:MEMBERSHIP_PAYMENT_POSTGRES_URL)) {
        $env:MEMBERSHIP_PAYMENT_POSTGRES_URL
    } else {
        'postgresql://postgres@127.0.0.1:5431/ai_temperate'
    }
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$builderPath = Join-Path $repositoryRoot `
    'loadtest\scripts\Build-MembershipTestWorkbook.mjs'
$sqlPath = Join-Path $repositoryRoot `
    'loadtest\sql\export-membership-test-workbook.sql'
foreach ($requiredPath in @($builderPath, $sqlPath, $NodeExecutable, $NodeModulesPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required workbook export dependency is unavailable: $requiredPath"
    }
}

$resolvedManifestPath = (Resolve-Path -LiteralPath $RunManifestPath).Path
$runRoot = Split-Path -Parent $resolvedManifestPath
$runManifest = Read-JsonFile -Path $resolvedManifestPath
$outputPath = [IO.Path]::GetFullPath($OutputFile)
$outputDirectory = Split-Path -Parent $outputPath
if (-not (Test-Path -LiteralPath $outputPath -PathType Leaf)) {
    throw "The input workbook to be replaced does not exist: $outputPath"
}
if (-not $Overwrite) {
    throw 'Output workbook already exists; provide -Overwrite to authorize atomic replacement.'
}
Assert-TargetWorkbookUnlocked -Path $outputPath

$previousPathProperty = $runManifest.PSObject.Properties['previousScenarioOrdersCsvPaths']
if ($null -eq $previousPathProperty) {
    throw 'run-manifest.json does not contain previousScenarioOrdersCsvPaths.'
}
$previousPaths = @($previousPathProperty.Value)
if ($previousPaths.Count -eq 0) {
    throw 'run-manifest.json contains no previous scenario evidence paths.'
}

$previousSources = [Collections.Generic.List[object]]::new()
foreach ($previousPath in $previousPaths) {
    if ([string]::IsNullOrWhiteSpace([string] $previousPath)) {
        throw 'run-manifest.json contains an empty previous scenario evidence path.'
    }
    $candidatePath = if ([IO.Path]::IsPathRooted([string] $previousPath)) {
        [string] $previousPath
    } else {
        Join-Path $runRoot ([string] $previousPath)
    }
    $previousSources.Add((Read-ScenarioEvidence -Path $candidatePath))
}
$previousGroupCodes = @($previousSources | ForEach-Object { $_.GroupCode } |
        Sort-Object -Unique)
if ($previousSources.Count -ne $earlyGroupCodes.Count -or
        ($previousGroupCodes -join ',') -cne (($earlyGroupCodes | Sort-Object) -join ',')) {
    throw 'previousScenarioOrdersCsvPaths must resolve to exactly the four E groups.'
}

$currentSources = @{}
foreach ($groupCode in $fixedGroupCodes) {
    $currentScenarioPath = Join-Path $runRoot "$groupCode\scenario-orders.csv"
    if (Test-Path -LiteralPath $currentScenarioPath -PathType Leaf) {
        $source = Read-ScenarioEvidence -Path $currentScenarioPath
        if ($source.GroupCode -cne $groupCode) {
            throw "Current scenario path $currentScenarioPath contains group $($source.GroupCode)."
        }
        $currentSources[$groupCode] = $source
    }
}

$selectedSources = [ordered]@{}
foreach ($groupCode in $fixedGroupCodes) {
    $matchingPrevious = @($previousSources | Where-Object { $_.GroupCode -ceq $groupCode })
    if ($groupCode -in $earlyGroupCodes) {
        if ($matchingPrevious.Count -ne 1) {
            throw "Group $groupCode requires exactly one source from previousScenarioOrdersCsvPaths."
        }
        $selected = $matchingPrevious[0]
        $alternatives = @($matchingPrevious)
        if ($currentSources.ContainsKey($groupCode)) {
            $alternatives += $currentSources[$groupCode]
        }
    } else {
        if (-not $currentSources.ContainsKey($groupCode)) {
            throw "Group $groupCode requires current-run scenario evidence: " +
                (Join-Path $runRoot "$groupCode\scenario-orders.csv")
        }
        $selected = $currentSources[$groupCode]
        $alternatives = @($matchingPrevious) + @($selected)
    }
    Assert-NoConflictingSource -Selected $selected -Alternatives $alternatives
    $selectedSources[$groupCode] = $selected
}

$expectedTotalRows = $ExpectedRowsPerGroup * $fixedGroupCodes.Count
$globalUsers = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$globalOrders = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$scopeRows = [Collections.Generic.List[object]]::new()
$groupEvidence = [Collections.Generic.List[object]]::new()
$scopeOrdinal = 0L

for ($groupIndex = 0; $groupIndex -lt $fixedGroupCodes.Count; $groupIndex += 1) {
    $groupCode = $fixedGroupCodes[$groupIndex]
    $source = $selectedSources[$groupCode]
    if ($source.Rows.Count -ne $ExpectedRowsPerGroup) {
        throw "Group $groupCode has $($source.Rows.Count) rows; expected $ExpectedRowsPerGroup."
    }

    $groupUsers = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $groupOrders = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $sourceRunIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $validatedRows = [Collections.Generic.List[object]]::new()
    foreach ($row in $source.Rows) {
        $rowGroupCode = Get-RequiredTextProperty -InputObject $row `
            -PropertyName 'group_code' -SourceDescription $source.Path
        if ($rowGroupCode -cne $groupCode) {
            throw "Scenario group mismatch in $($source.Path): expected $groupCode, received $rowGroupCode."
        }
        $userIdText = Get-RequiredTextProperty -InputObject $row `
            -PropertyName 'user_id' -SourceDescription $source.Path
        try {
            $userId = [long]::Parse(
                $userIdText,
                [Globalization.NumberStyles]::None,
                [Globalization.CultureInfo]::InvariantCulture)
        } catch {
            throw "Scenario user_id is not a canonical positive BIGINT in $($source.Path): $userIdText"
        }
        if ($userId -le 0 -or $userId.ToString(
                [Globalization.CultureInfo]::InvariantCulture) -cne $userIdText) {
            throw "Scenario user_id is not a canonical positive BIGINT in $($source.Path): $userIdText"
        }
        $orderId = Get-RequiredTextProperty -InputObject $row `
            -PropertyName 'order_id' -SourceDescription $source.Path
        if ($orderId -cnotmatch '^[A-Za-z0-9_-]{22}$') {
            throw "Scenario order_id is not a canonical 22-character Base64URL value: $orderId"
        }
        $sourceRunId = Get-RequiredTextProperty -InputObject $row `
            -PropertyName 'run_id' -SourceDescription $source.Path
        if (-not $groupUsers.Add($userIdText) -or -not $groupOrders.Add($orderId)) {
            throw "Group $groupCode contains a duplicate user or order."
        }
        if (-not $globalUsers.Add($userIdText) -or -not $globalOrders.Add($orderId)) {
            throw "Cross-group duplicate user or order detected in group $groupCode."
        }
        $null = $sourceRunIds.Add($sourceRunId)
        $validatedRows.Add([pscustomobject]@{
                Row = $row
                UserId = $userId
                UserIdText = $userIdText
                OrderId = $orderId
                SourceRunId = $sourceRunId
            })
    }
    if ($groupUsers.Count -ne $ExpectedRowsPerGroup -or
            $groupOrders.Count -ne $ExpectedRowsPerGroup -or
            $sourceRunIds.Count -ne 1) {
        throw "Group $groupCode does not contain one run with exactly $ExpectedRowsPerGroup distinct users and orders."
    }

    foreach ($validated in @($validatedRows | Sort-Object UserId)) {
        $row = $validated.Row
        $scopeOrdinal += 1
        $scopeRows.Add([pscustomobject][ordered]@{
                scope_ordinal = $scopeOrdinal
                group_ordinal = $groupIndex + 1
                run_id = $validated.SourceRunId
                wave_code = Get-RequiredTextProperty -InputObject $row `
                    -PropertyName 'wave_code' -SourceDescription $source.Path
                group_code = $groupCode
                trace_id = Get-RequiredTextProperty -InputObject $row `
                    -PropertyName 'trace_id' -SourceDescription $source.Path
                user_id = $validated.UserIdText
                target_tier = Get-RequiredTextProperty -InputObject $row `
                    -PropertyName 'target_tier' -SourceDescription $source.Path
                order_id_base64url = $validated.OrderId
                planned_expires_at = Get-RequiredTextProperty -InputObject $row `
                    -PropertyName 'expires_at' -SourceDescription $source.Path
                planned_hard_close_at = Get-RequiredTextProperty -InputObject $row `
                    -PropertyName 'hard_close_at' -SourceDescription $source.Path
                target_offset_millis = Get-RequiredTextProperty -InputObject $row `
                    -PropertyName 'target_offset_millis' -SourceDescription $source.Path
                target_at = Get-RequiredTextProperty -InputObject $row `
                    -PropertyName 'target_at' -SourceDescription $source.Path
            })
    }

    $evidenceDirectory = Split-Path -Parent $source.Path
    $groupVerdictPath = Join-Path $evidenceDirectory 'verdict.json'
    $serverVerdictPath = Join-Path $evidenceDirectory 'server-time-verdict.csv'
    $groupVerdict = Read-JsonFile -Path $groupVerdictPath
    $verdict = Get-RequiredTextProperty -InputObject $groupVerdict `
        -PropertyName 'verdict' -SourceDescription $groupVerdictPath
    if ($verdict -cne 'PASS') {
        throw "Group $groupCode verdict is $verdict; every group must be PASS."
    }
    if (-not (Test-Path -LiteralPath $serverVerdictPath -PathType Leaf)) {
        throw "Server-time verdict evidence is missing: $serverVerdictPath"
    }
    $serverRows = @(Import-Csv -LiteralPath $serverVerdictPath)
    if ($serverRows.Count -ne $ExpectedRowsPerGroup) {
        throw "Group $groupCode server-time verdict has $($serverRows.Count) rows; expected $ExpectedRowsPerGroup."
    }
    $serverFailureRows = @($serverRows | Where-Object {
            $failureProperty = $_.PSObject.Properties['failure']
            $null -eq $failureProperty -or
                -not [string]::IsNullOrWhiteSpace([string] $failureProperty.Value)
        }).Count
    if ($serverFailureRows -ne 0) {
        throw "Group $groupCode contains $serverFailureRows failed server-time verdict rows."
    }
    $generatedAt = if ($null -ne $groupVerdict.PSObject.Properties['completedAt'] -and
            -not [string]::IsNullOrWhiteSpace([string] $groupVerdict.completedAt)) {
        [string] $groupVerdict.completedAt
    } else {
        (Get-Item -LiteralPath $groupVerdictPath).LastWriteTimeUtc.ToString('O')
    }
    $groupEvidence.Add([ordered]@{
            groupOrdinal = $groupIndex + 1
            groupCode = $groupCode
            sourceRunId = @($sourceRunIds)[0]
            scenarioOrdersCsv = $source.Path
            expectedRows = $ExpectedRowsPerGroup
            actualRows = $source.Rows.Count
            distinctUsers = $groupUsers.Count
            distinctOrders = $groupOrders.Count
            verdict = $verdict
            serverFailureRows = $serverFailureRows
            evidenceGeneratedAt = $generatedAt
        })
}

if ($scopeRows.Count -ne $expectedTotalRows -or
        $globalUsers.Count -ne $expectedTotalRows -or
        $globalOrders.Count -ne $expectedTotalRows) {
    throw "Combined scope is not exactly $expectedTotalRows distinct users and orders."
}

$suiteVerdictPath = Join-Path $runRoot 'verdict.json'
$runStatePath = Join-Path $runRoot 'run-state.json'
$suiteVerdict = Read-JsonFile -Path $suiteVerdictPath
$runState = Read-JsonFile -Path $runStatePath
$suiteStatus = Get-RequiredTextProperty -InputObject $suiteVerdict `
    -PropertyName 'verdict' -SourceDescription $suiteVerdictPath
if ($CompletionPolicy -ceq 'RequireSuitePass' -and $suiteStatus -cne 'PASS') {
    throw "CompletionPolicy RequireSuitePass rejected Suite status $suiteStatus; the workbook was not changed."
}

$masterRunId = if ($null -ne $suiteVerdict.PSObject.Properties['runId']) {
    [string] $suiteVerdict.runId
} else {
    Get-RequiredTextProperty -InputObject $runManifest -PropertyName 'runId' `
        -SourceDescription $resolvedManifestPath
}
$suitePhase = if ($null -ne $runState.PSObject.Properties['wave']) {
    [string] $runState.wave
} elseif ($null -ne $runState.PSObject.Properties['state']) {
    [string] $runState.state
} elseif ($null -ne $runState.PSObject.Properties['phase']) {
    [string] $runState.phase
} else { '' }
$suiteFailureStage = if ($null -ne $suiteVerdict.PSObject.Properties['originStage']) {
    [string] $suiteVerdict.originStage
} else { '' }
$suiteFailureMessage = if ($null -ne $suiteVerdict.PSObject.Properties['primaryMessage']) {
    [string] $suiteVerdict.primaryMessage
} elseif ($null -ne $suiteVerdict.PSObject.Properties['message']) {
    [string] $suiteVerdict.message
} else { '' }
$runScale = if ($null -ne $runManifest.PSObject.Properties['runScale'] -and
        -not [string]::IsNullOrWhiteSpace([string] $runManifest.runScale)) {
    [string] $runManifest.runScale
} else {
    'PERFORMANCE_' + [Math]::Floor($expectedTotalRows / 1000) + 'K'
}

if ($ValidationOnly) {
    return [pscustomobject]@{
        ExpectedRows = $expectedTotalRows
        GroupVerdicts = '8/8 PASS'
        SuiteStatus = $suiteStatus
        FullyTested = ($suiteStatus -ceq 'PASS')
        ScopeValidated = $true
    }
}

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'membership-workbook-export-' + [guid]::NewGuid().ToString('N'))
$stagingWorkbook = Join-Path $outputDirectory (
    '.' + [IO.Path]::GetFileNameWithoutExtension($outputPath) + '.' +
    [guid]::NewGuid().ToString('N') + '.staging.xlsx')
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null

try {
    $scopeCsv = Join-Path $temporaryRoot 'scope.csv'
    $metadataPath = Join-Path $temporaryRoot 'metadata.json'
    $previewDirectory = Join-Path $temporaryRoot 'previews'
    $verificationPath = Join-Path $temporaryRoot 'verification.json'
    $psqlOutputPath = Join-Path $temporaryRoot 'psql-output.txt'
    $nodeWorkDirectory = Join-Path $temporaryRoot 'node-work'
    New-Item -ItemType Directory -Path $previewDirectory, $nodeWorkDirectory | Out-Null

    $scopeRows | Export-Csv -LiteralPath $scopeCsv -NoTypeInformation -Encoding utf8
    $metadata = [ordered]@{
        schemaVersion = 1
        masterRunId = $masterRunId
        runScale = $runScale
        sourceFingerprint = if ($null -ne $runManifest.PSObject.Properties['sourceFingerprint']) {
            [string] $runManifest.sourceFingerprint
        } else { '' }
        expectedRowsPerGroup = $ExpectedRowsPerGroup
        expectedGroupCodes = $fixedGroupCodes
        expectedTotalRows = $expectedTotalRows
        completionPolicy = $CompletionPolicy
        suiteStatus = $suiteStatus
        suitePhase = $suitePhase
        suiteFailureStage = $suiteFailureStage
        suiteFailureMessage = $suiteFailureMessage
        generatedAtUtc = [datetimeoffset]::UtcNow.ToString('O')
        groupEvidence = @($groupEvidence)
    }
    Write-Utf8Json -Path $metadataPath -Value $metadata

    $psqlCommand = Get-Command psql -CommandType Application -ErrorAction SilentlyContinue
    if ($null -eq $psqlCommand) {
        throw 'psql is unavailable; the workbook was not changed.'
    }
    $csvPaths = [ordered]@{
        orders_csv = Join-Path $temporaryRoot 'orders.csv'
        callbacks_csv = Join-Path $temporaryRoot 'callbacks.csv'
        quotas_csv = Join-Path $temporaryRoot 'quotas.csv'
        id_mapping_csv = Join-Path $temporaryRoot 'id-mapping.csv'
        consistency_csv = Join-Path $temporaryRoot 'consistency.csv'
        metrics_csv = Join-Path $temporaryRoot 'metrics.csv'
    }
    $runSqlPath = Join-Path $temporaryRoot 'export-membership-test-workbook-run.sql'
    $sqlText = Get-Content -LiteralPath $sqlPath -Raw
    $sqlText = $sqlText.Replace('__EXPECTED_TOTAL__',
        $expectedTotalRows.ToString([Globalization.CultureInfo]::InvariantCulture))
    $sqlText = $sqlText.Replace('__SCOPE_CSV__',
        (Convert-ToPsqlPath $scopeCsv).Replace("'", "''"))
    $sqlText = $sqlText.Replace('__ORDERS_CSV__',
        (Convert-ToPsqlPath $csvPaths.orders_csv).Replace("'", "''"))
    $sqlText = $sqlText.Replace('__CALLBACKS_CSV__',
        (Convert-ToPsqlPath $csvPaths.callbacks_csv).Replace("'", "''"))
    $sqlText = $sqlText.Replace('__QUOTAS_CSV__',
        (Convert-ToPsqlPath $csvPaths.quotas_csv).Replace("'", "''"))
    $sqlText = $sqlText.Replace('__ID_MAPPING_CSV__',
        (Convert-ToPsqlPath $csvPaths.id_mapping_csv).Replace("'", "''"))
    $sqlText = $sqlText.Replace('__CONSISTENCY_CSV__',
        (Convert-ToPsqlPath $csvPaths.consistency_csv).Replace("'", "''"))
    $sqlText = $sqlText.Replace('__METRICS_CSV__',
        (Convert-ToPsqlPath $csvPaths.metrics_csv).Replace("'", "''"))
    [IO.File]::WriteAllText(
        $runSqlPath,
        $sqlText,
        [Text.UTF8Encoding]::new($false))
    $psqlArguments = @(
        '-w', $PostgresUrl,
        '-v', 'ON_ERROR_STOP=1'
    )
    $psqlArguments += @('-f', $runSqlPath)
    $psqlOutput = & $psqlCommand.Source @psqlArguments 2>&1 | Out-String
    [IO.File]::WriteAllText(
        $psqlOutputPath,
        $psqlOutput,
        [Text.UTF8Encoding]::new($false))
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL read-only workbook extraction failed; the workbook was not changed.'
    }
    foreach ($csvPath in $csvPaths.Values) {
        if (-not (Test-Path -LiteralPath $csvPath -PathType Leaf)) {
            throw "PostgreSQL extraction did not produce the required CSV: $csvPath"
        }
    }

    $nodeModulesJunction = Join-Path $nodeWorkDirectory 'node_modules'
    New-Item -ItemType Junction -Path $nodeModulesJunction -Target $NodeModulesPath | Out-Null
    $copiedBuilder = Join-Path $nodeWorkDirectory 'Build-MembershipTestWorkbook.mjs'
    Copy-Item -LiteralPath $builderPath -Destination $copiedBuilder
    $artifactMarkerPath = Get-ArtifactMarkerPath
    $builderArguments = @(
        $copiedBuilder,
        '--input-workbook', $outputPath,
        '--output-workbook', $stagingWorkbook,
        '--metadata', $metadataPath,
        '--data-directory', $temporaryRoot,
        '--preview-directory', $previewDirectory,
        '--verification-file', $verificationPath
    )

    # 该标记必须紧邻第一次工作簿创作命令，确保一次运行只登记一个 Excel 编辑产物。
    & $NodeExecutable $artifactMarkerPath `
        --operation-kind edit --expected-output-count 1 --output-format xlsx
    if ($LASTEXITCODE -ne 0) {
        throw 'Spreadsheet artifact operation marker failed; the workbook was not changed.'
    }
    & $NodeExecutable --expose-gc `
        "--max-old-space-size=$NodeMaxOldSpaceSizeMb" @builderArguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Workbook construction or verification failed; the original workbook was not changed.'
    }
    if (-not (Test-Path -LiteralPath $stagingWorkbook -PathType Leaf) -or
            -not (Test-Path -LiteralPath $verificationPath -PathType Leaf)) {
        throw 'Workbook builder did not publish its verified staging files.'
    }
    $verification = Read-JsonFile -Path $verificationPath
    if (@($verification.sheets).Count -ne 8 -or
            [int] $verification.orderRows -ne $expectedTotalRows -or
            [int] $verification.callbackRows -ne $expectedTotalRows -or
            [int] $verification.quotaRows -ne $expectedTotalRows -or
            [int] $verification.idMappingRows -ne $expectedTotalRows -or
            [int] $verification.formulaErrorCount -ne 0) {
        throw 'Workbook staging verification did not satisfy the export contract.'
    }

    # staging 与目标位于同一目录；替换失败（包括文件锁定）时原工作簿保持不变且不创建备份。
    [IO.File]::Replace($stagingWorkbook, $outputPath, $null)

    [pscustomobject]@{
        OutputFile = $outputPath
        ExpectedRows = $expectedTotalRows
        GroupVerdicts = '8/8 PASS'
        SuiteStatus = $suiteStatus
        FullyTested = ($suiteStatus -ceq 'PASS')
        SnapshotStatus = '已归档'
    }
} finally {
    if (Test-Path -LiteralPath $stagingWorkbook -PathType Leaf) {
        Remove-Item -LiteralPath $stagingWorkbook -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
