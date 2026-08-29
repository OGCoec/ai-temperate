[CmdletBinding()]
param(
    [string] $HostName = 'localhost',
    [int] $Port = 6655,
    [string] $Protocol = 'http',
    [string] $UsersCsv = 'loadtest/local/loadtest-users.csv',
    [string] $SoakId = '',
    [ValidateSet('W01', 'W02', 'W03', 'W04', 'W05', 'W06', 'W07', 'W08')]
    [string] $StartWave = 'W01',
    [switch] $StartImmediately,
    [switch] $NoWaveGaps,
    [switch] $PreflightOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runner = Join-Path $PSScriptRoot 'Invoke-MembershipLoadtestScenario.ps1'
$approvedUserIds = @(
    '72659006262480896', '73014701344296960', '74891801495998464', '76721355290185728',
    '84736921162616832', '84739559597936640', '84742296792338432', '84745417706835968',
    '84746552547086336', '84753114204344320', '84754367089086464', '84755204414771200',
    '84758509811535872', '84758866549673984', '84759380653903872', '84760794662834176'
)
$sourcePaths = @(
    'ai-temperate-common',
    'ai-temperate-model',
    'ai-temperate-mapper',
    'ai-temperate-service',
    'ai-temperate-web',
    'loadtest',
    'sql',
    'docs',
    'pom.xml'
)

function Get-TextSha256([string] $Value) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [System.Security.Cryptography.SHA256]::HashData($bytes)
    return [Convert]::ToHexString($hash).ToLowerInvariant()
}

function Get-SourceFingerprint {
    Push-Location $repoRoot
    try {
        $head = (& git rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Unable to read the main project Git SHA.' }
        $diff = (& git diff --binary HEAD -- @sourcePaths | Out-String)
        if ($LASTEXITCODE -ne 0) { throw 'Unable to fingerprint tracked source changes.' }
        $untracked = @(& git ls-files --others --exclude-standard -- @sourcePaths |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object)
        if ($LASTEXITCODE -ne 0) { throw 'Unable to fingerprint untracked source files.' }
        $untrackedFacts = foreach ($relative in $untracked) {
            $absolute = Join-Path $repoRoot $relative
            if (Test-Path -LiteralPath $absolute -PathType Leaf) {
                $fileHash = (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash.ToLowerInvariant()
                "$relative=$fileHash"
            }
        }
        return Get-TextSha256 (($head, (Get-TextSha256 $diff)) + $untrackedFacts -join "`n")
    } finally {
        Pop-Location
    }
}

function Resolve-PostgresUrl {
    if (-not [string]::IsNullOrWhiteSpace($env:MEMBERSHIP_PAYMENT_POSTGRES_URL)) {
        return $env:MEMBERSHIP_PAYMENT_POSTGRES_URL
    }
    if (-not [string]::IsNullOrWhiteSpace($env:POSTGRES_URL)) {
        return $env:POSTGRES_URL
    }
    return 'postgresql://postgres@127.0.0.1:5431/ai_temperate'
}

function Assert-NoExistingActiveOrders {
    $ids = $approvedUserIds -join ','
    $query = @"
SELECT COUNT(*)
FROM membership_order
WHERE login_identity_id IN ($ids)
  AND (
      status IN (0, 1)
      OR (status = 2 AND entitlement_resolution IS NULL)
  );
"@
    $count = (& psql -w (Resolve-PostgresUrl) -At -v ON_ERROR_STOP=1 -c $query).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect existing active membership orders.' }
    if ([long]$count -ne 0L) {
        throw "The fixed test users still own $count active membership orders."
    }
}

function Assert-CleanMembershipOrderBaseline {
    $ids = $approvedUserIds -join ','
    $query = @"
SELECT COUNT(*)
FROM membership_order
WHERE login_identity_id IN ($ids);
"@
    $count = (& psql -w (Resolve-PostgresUrl) -At -v ON_ERROR_STOP=1 -c $query).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the fixed users membership order baseline.' }
    if ([long]$count -ne 0L) {
        throw "The fixed test users still own $count historical membership orders."
    }
}

function Assert-ApprovedUsersAtFreeBaseline {
    $ids = $approvedUserIds -join ','
    $query = @"
SELECT COUNT(*)
FROM user_membership_quota
WHERE login_identity_id IN ($ids)
  AND membership_tier = 0
  AND quota_balance_minor = 5000
  AND quota_period_started_at IS NULL
  AND quota_period_ends_at IS NOT NULL
  AND membership_expires_at IS NULL;
"@
    $count = (& psql -w (Resolve-PostgresUrl) -At -v ON_ERROR_STOP=1 -c $query).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the fixed users FREE quota baseline.' }
    if ([long]$count -ne $approvedUserIds.Count) {
        throw "Only $count of $($approvedUserIds.Count) fixed users are at the required FREE baseline."
    }
}

function Order-UsersByMembershipTier([string] $UsersCsvValue) {
    $usersPath = if ([System.IO.Path]::IsPathRooted($UsersCsvValue)) {
        $UsersCsvValue
    } else {
        Join-Path $repoRoot $UsersCsvValue
    }
    $rows = @(Import-Csv -LiteralPath $usersPath)
    $actualIds = @($rows | ForEach-Object { [string]$_.userId })
    if ($rows.Count -ne $approvedUserIds.Count `
        -or @($approvedUserIds | Where-Object { $actualIds -notcontains $_ }).Count -ne 0) {
        throw 'Wave Token CSV no longer contains exactly the sixteen approved users.'
    }
    $ids = $approvedUserIds -join ','
    $query = @"
SELECT login_identity_id, membership_tier
FROM user_membership_quota
WHERE login_identity_id IN ($ids)
ORDER BY login_identity_id;
"@
    $tierLines = @(& psql -w (Resolve-PostgresUrl) -At -F '|' -v ON_ERROR_STOP=1 -c $query)
    if ($LASTEXITCODE -ne 0 -or $tierLines.Count -ne $approvedUserIds.Count) {
        throw 'Unable to read all fixed-user membership tiers before the wave.'
    }
    $tierByUserId = @{}
    foreach ($line in $tierLines) {
        $parts = @($line -split '\|', 2)
        if ($parts.Count -ne 2 -or $parts[0] -notin $approvedUserIds) {
            throw 'Membership tier ordering query returned an unexpected user.'
        }
        $tierByUserId[$parts[0]] = [int]$parts[1]
    }
    # 技术波次会真实发放权益；按当前等级从低到高分配账号，均匀消耗合法升级台阶，
    # 只重排 Git 忽略的 Token 行，不重置会员数据，也不把 Token 写入日志或证据。
    $orderedRows = @($rows | Sort-Object `
        @{ Expression = { $tierByUserId[[string]$_.userId] }; Ascending = $true }, `
        @{ Expression = { [array]::IndexOf($approvedUserIds, [string]$_.userId) }; Ascending = $true })
    $orderedRows | Export-Csv -LiteralPath $usersPath -NoTypeInformation -Encoding UTF8
}

function Save-State(
    [string] $State,
    [string] $Wave,
    [datetimeoffset] $StartedAt,
    [datetimeoffset] $NextBoundary,
    [string] $StatePath) {
    [ordered]@{
        phase = 'LOCAL'
        state = $State
        wave = $Wave
        startedAt = $StartedAt.ToUniversalTime().ToString('O')
        nextBoundaryAt = $NextBoundary.ToUniversalTime().ToString('O')
        updatedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $StatePath -Encoding UTF8
}

function Wait-ToBoundary(
    [datetimeoffset] $StartedAt,
    [timespan] $Offset,
    [string] $Wave,
    [string] $StatePath) {
    $boundary = $StartedAt.Add($Offset)
    while ([datetimeoffset]::UtcNow -lt $boundary) {
        Save-State 'OBSERVING' $Wave $StartedAt $boundary $StatePath
        $remaining = $boundary - [datetimeoffset]::UtcNow
        Start-Sleep -Seconds ([Math]::Max(1, [Math]::Min(60, [int][Math]::Ceiling($remaining.TotalSeconds))))
    }
}

function Wait-ToWaveStartBoundary(
    [datetimeoffset] $StartedAt,
    [timespan] $Offset,
    [string] $ObservedWave,
    [string] $TargetWave,
    [string] $StatePath,
    [bool] $Resume,
    [bool] $StartImmediately,
    [bool] $NoWaveGaps,
    [string] $RequestedStartWave) {
    # 断点续跑默认保留原始 T0 的时间表；用户可以只提前当前断点波次，
    # 也可以明确要求剩余波次连续运行，二者都不会改变已经完成的证据。
    if ($NoWaveGaps -or ($Resume -and $StartImmediately -and $RequestedStartWave -eq $TargetWave)) {
        Save-State 'RESUMING' $TargetWave $StartedAt ([datetimeoffset]::UtcNow) $StatePath
        return
    }
    Wait-ToBoundary $StartedAt $Offset $ObservedWave $StatePath
}

function Save-WaveResults(
    [System.Collections.Generic.List[object]] $Results,
    [string] $ResultsPath) {
    # 每个子波次完成后立即落盘，避免安全中断发生在长观察窗时丢失已经通过的检查点。
    $Results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ResultsPath -Encoding UTF8
}

function Import-CompletedSplitWaveResults(
    [string] $AbsoluteOutput,
    [System.Collections.Generic.List[object]] $Results,
    [string] $ResultsPath) {
    # W03 由两个独立且都必须通过的子波次组成；若旧 Runner 在两者完成后的观察窗被停止，
    # 只从原始 verdict.json 恢复汇总引用，绝不重新生成或改写测试裁决。
    foreach ($splitWave in @('W03-A', 'W03-B')) {
        if ($Results | Where-Object { $_.wave -eq $splitWave -and $_.verdict -eq 'PASS' }) {
            continue
        }
        $splitRoot = Join-Path $AbsoluteOutput $splitWave
        $verdictFile = Get-ChildItem -LiteralPath $splitRoot -Filter 'verdict.json' -Recurse -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($null -eq $verdictFile) {
            continue
        }
        $verdict = Get-Content -Raw -LiteralPath $verdictFile.FullName | ConvertFrom-Json
        if ($verdict.verdict -ne 'PASS') {
            throw "$splitWave existing verdict is $($verdict.verdict); checkpoint recovery is forbidden."
        }
        $Results.Add([ordered]@{
            wave = $splitWave
            scenario = [string]$verdict.scenario
            verdict = 'PASS'
            outputDirectory = [string]$verdict.outputDirectory
            completedAt = $verdictFile.LastWriteTimeUtc.ToString('O')
            recoveredFromVerdict = $verdictFile.FullName
        })
    }
    Save-WaveResults $Results $ResultsPath
}

function Test-WaveCheckpointComplete(
    [string] $Wave,
    [System.Collections.Generic.List[object]] $Results) {
    $expectedResults = if ($Wave -eq 'W03') { @('W03-A', 'W03-B') } else { @($Wave) }
    foreach ($expectedResult in $expectedResults) {
        if (-not ($Results | Where-Object { $_.wave -eq $expectedResult -and $_.verdict -eq 'PASS' })) {
            return $false
        }
    }
    return $true
}

function Invoke-Wave(
    [string] $Wave,
    [string] $Scenario,
    [string] $Jmx,
    [int] $Threads,
    [int] $Concurrency,
    [string] $Fingerprint,
    [string] $OutputRoot,
    [System.Collections.Generic.List[object]] $Results,
    [string] $ResultsPath) {
    if ((Get-SourceFingerprint) -ne $Fingerprint) {
        throw "Source fingerprint changed before $Wave; formal soak evidence cannot be merged."
    }
    Order-UsersByMembershipTier $UsersCsv
    $parameters = @{
        Scenario = $Scenario
        Jmx = $Jmx
        Mode = 'loadtest-realtime'
        Threads = $Threads
        Concurrency = $Concurrency
        HostName = $HostName
        Port = $Port
        Protocol = $Protocol
        UsersCsv = $UsersCsv
        OutputRoot = "$OutputRoot/$Wave"
        SettleSeconds = 40
    }
    & $runner @parameters
    # 子 PowerShell Runner 会在真实失败时抛出终止异常，并在成功时写出 verdict.json；
    # 其内部为探测 Docker/Redis 等降级路径而执行的原生命令可能留下非零 LASTEXITCODE，
    # 该进程级遗留值不能覆盖 Runner 已完成的 PASS 裁决。
    $waveRoot = Join-Path $repoRoot "$OutputRoot/$Wave"
    $verdictFile = Get-ChildItem -LiteralPath $waveRoot -Filter 'verdict.json' -Recurse |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $verdictFile) { throw "$Wave did not produce verdict.json." }
    $verdict = Get-Content -Raw -LiteralPath $verdictFile.FullName | ConvertFrom-Json
    if ($verdict.verdict -ne 'PASS') { throw "$Wave verdict is $($verdict.verdict)." }
    $Results.Add([ordered]@{
        wave = $Wave
        scenario = $Scenario
        verdict = $verdict.verdict
        outputDirectory = $verdict.outputDirectory
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    })
    Save-WaveResults $Results $ResultsPath
}

foreach ($command in @('git', 'jmeter', 'java', 'mvn', 'psql', 'docker')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is unavailable: $command"
    }
}
$profiles = @($env:SPRING_PROFILES_ACTIVE -split ',' |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ })
if ($profiles -notcontains 'loadtest-realtime') {
    throw 'Local soak requires SPRING_PROFILES_ACTIVE to include loadtest-realtime.'
}
$baseUrl = "$Protocol`://$HostName`:$Port"
$health = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/actuator/health/readiness" -TimeoutSec 10
if ($health.StatusCode -lt 200 -or $health.StatusCode -ge 300) {
    throw 'Local application readiness check failed.'
}
Assert-NoExistingActiveOrders
$isResume = $StartWave -ne 'W01'
if ($StartWave -eq 'W01') {
    Assert-CleanMembershipOrderBaseline
    Assert-ApprovedUsersAtFreeBaseline
} elseif ([string]::IsNullOrWhiteSpace($SoakId)) {
    throw 'Checkpoint resume requires the original SoakId.'
}
$fingerprint = Get-SourceFingerprint

if ($PreflightOnly) {
    Write-Host "LOCAL SOAK PREFLIGHT PASS fingerprint=$fingerprint"
    return
}

if ([string]::IsNullOrWhiteSpace($SoakId)) {
    $SoakId = 'membership-payment-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
}
$outputRoot = "loadtest-output/soak/$SoakId/local"
$absoluteOutput = Join-Path $repoRoot $outputRoot
New-Item -ItemType Directory -Force -Path $absoluteOutput | Out-Null
$statePath = Join-Path $absoluteOutput 'soak-state.json'
$manifestPath = Join-Path $absoluteOutput 'local-manifest.json'
$resultsPath = Join-Path $absoluteOutput 'local-wave-results.json'
$deploymentGatePath = Join-Path $absoluteOutput 'deployment-gate.json'
$finalDatabaseScanPath = Join-Path $absoluteOutput 'final-database-scan.txt'
$infrastructureBeforePath = Join-Path $absoluteOutput 'infrastructure-before.json'
$infrastructureAfterPath = Join-Path $absoluteOutput 'infrastructure-after.json'
$infrastructureVerdictPath = Join-Path $absoluteOutput 'infrastructure-verdict.json'
$results = [System.Collections.Generic.List[object]]::new()
$waveOrder = @('W01', 'W02', 'W03', 'W04', 'W05', 'W06', 'W07', 'W08')
$startWaveIndex = [array]::IndexOf($waveOrder, $StartWave)

if ($isResume) {
    $hasResumeArtifacts =
        (Test-Path -LiteralPath $manifestPath -PathType Leaf) -and
        (Test-Path -LiteralPath $resultsPath -PathType Leaf) -and
        (Test-Path -LiteralPath $infrastructureBeforePath -PathType Leaf)
    if (-not $hasResumeArtifacts) {
        throw 'Checkpoint resume requires the original manifest, wave results and infrastructure baseline.'
    }
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $startedAt = [datetimeoffset]::Parse(
            [string]$manifest.formalStartedAt,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind)
    $existingResults = @(Get-Content -Raw -LiteralPath $resultsPath | ConvertFrom-Json)
    foreach ($existingResult in $existingResults) {
        $results.Add($existingResult)
    }
    Import-CompletedSplitWaveResults $absoluteOutput $results $resultsPath
    $requiredPriorWaves = @($waveOrder[0..($startWaveIndex - 1)])
    foreach ($requiredWave in $requiredPriorWaves) {
        if (-not (Test-WaveCheckpointComplete $requiredWave $results)) {
            throw "Checkpoint resume is missing a PASS result for $requiredWave."
        }
    }
    [ordered]@{
        soakId = $SoakId
        resumedAt = [datetimeoffset]::UtcNow.ToString('O')
        startWave = $StartWave
        originalSourceFingerprint = [string]$manifest.sourceFingerprint
        resumeSourceFingerprint = $fingerprint
    } | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $absoluteOutput 'resume-source-fingerprint.json') -Encoding UTF8
} else {
    $startedAt = [datetimeoffset]::UtcNow
    [ordered]@{
        soakId = $SoakId
        phase = 'LOCAL'
        formalStartedAt = $startedAt.ToString('O')
        sourceFingerprint = $fingerprint
        expectedActualOrders = 148
        expectedHttpRequestsMinimum = 25000
        timingContract = [ordered]@{
            precheck = 'PT2M'
            pending = 'PT5M'
            closing = 'PT5M'
        }
        waves = @(
            [ordered]@{ wave = 'W01'; orders = 30 },
            [ordered]@{ wave = 'W02'; orders = 28 },
            [ordered]@{ wave = 'W03'; orders = 20 },
            [ordered]@{ wave = 'W04'; orders = 25 },
            [ordered]@{ wave = 'W05'; orders = 11 },
            [ordered]@{ wave = 'W06'; orders = 12 },
            [ordered]@{ wave = 'W07'; orders = 10 },
            [ordered]@{ wave = 'W08'; orders = 12 }
        )
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
}

try {
    if (-not $isResume) {
        & (Join-Path $PSScriptRoot 'Save-MembershipPaymentInfrastructureEvidence.ps1') `
            -Capture BEFORE `
            -InspectionBaseUrl $baseUrl `
            -OutputFile $infrastructureBeforePath
        if ($LASTEXITCODE -ne 0) { throw 'Local infrastructure baseline capture failed.' }

        Wait-ToBoundary $startedAt ([timespan]::FromMinutes(2)) 'PRECHECK' $statePath
        Invoke-Wave 'W01' 'membership-order-state-machine' `
            'loadtest/jmeter/membership-order-state-machine.jmx' 30 0 `
            $fingerprint $outputRoot $results $resultsPath
    }
    Wait-ToWaveStartBoundary $startedAt ([timespan]::FromMinutes(62)) 'W01' 'W02' `
        $statePath $isResume $StartImmediately.IsPresent $NoWaveGaps.IsPresent $StartWave

    if ($startWaveIndex -le [array]::IndexOf($waveOrder, 'W02')) {
        Invoke-Wave 'W02' 'membership-marker-stage-matrix' `
            'loadtest/jmeter/membership-marker-stage-matrix.jmx' 28 0 `
            $fingerprint $outputRoot $results $resultsPath
    }
    Wait-ToWaveStartBoundary $startedAt ([timespan]::FromMinutes(152)) 'W02' 'W03' `
        $statePath $isResume $StartImmediately.IsPresent $NoWaveGaps.IsPresent $StartWave

    if ($startWaveIndex -le [array]::IndexOf($waveOrder, 'W03')) {
        Invoke-Wave 'W03-A' 'membership-callback-transport' `
            'loadtest/jmeter/membership-callback-transport.jmx' 15 0 `
            $fingerprint $outputRoot $results $resultsPath
        Invoke-Wave 'W03-B' 'membership-callback-identity' `
            'loadtest/jmeter/membership-callback-race-idempotency.jmx' 5 10 `
            $fingerprint $outputRoot $results $resultsPath
    }
    Wait-ToWaveStartBoundary $startedAt ([timespan]::FromMinutes(212)) 'W03' 'W04' `
        $statePath $isResume $StartImmediately.IsPresent $NoWaveGaps.IsPresent $StartWave

    if ($startWaveIndex -le [array]::IndexOf($waveOrder, 'W04')) {
        Invoke-Wave 'W04' 'membership-order-concurrency' `
            'loadtest/jmeter/membership-order-concurrency.jmx' 1 0 `
            $fingerprint $outputRoot $results $resultsPath
    }
    Wait-ToWaveStartBoundary $startedAt ([timespan]::FromMinutes(332)) 'W04' 'W05' `
        $statePath $isResume $StartImmediately.IsPresent $NoWaveGaps.IsPresent $StartWave

    if ($startWaveIndex -le [array]::IndexOf($waveOrder, 'W05')) {
        Invoke-Wave 'W05' 'membership-rabbit-state-timing' `
            'loadtest/jmeter/membership-rabbit-state-timing.jmx' 11 0 `
            $fingerprint $outputRoot $results $resultsPath
    }
    Wait-ToWaveStartBoundary $startedAt ([timespan]::FromMinutes(392)) 'W05' 'W06' `
        $statePath $isResume $StartImmediately.IsPresent $NoWaveGaps.IsPresent $StartWave

    if ($startWaveIndex -le [array]::IndexOf($waveOrder, 'W06')) {
        Invoke-Wave 'W06' 'membership-recovery-terminal-cleanup' `
            'loadtest/jmeter/membership-recovery-terminal-cleanup.jmx' 12 0 `
            $fingerprint $outputRoot $results $resultsPath
    }
    Wait-ToWaveStartBoundary $startedAt ([timespan]::FromMinutes(482)) 'W06' 'W07' `
        $statePath $isResume $StartImmediately.IsPresent $NoWaveGaps.IsPresent $StartWave

    if ($startWaveIndex -le [array]::IndexOf($waveOrder, 'W07')) {
        Invoke-Wave 'W07' 'membership-rejected-closing-matrix' `
            'loadtest/jmeter/membership-marker-stage-matrix.jmx' 10 0 `
            $fingerprint $outputRoot $results $resultsPath
    }
    Wait-ToWaveStartBoundary $startedAt ([timespan]::FromMinutes(572)) 'W07' 'W08' `
        $statePath $isResume $StartImmediately.IsPresent $NoWaveGaps.IsPresent $StartWave

    if ($startWaveIndex -le [array]::IndexOf($waveOrder, 'W08')) {
        Invoke-Wave 'W08' 'membership-long-observation' `
            'loadtest/jmeter/membership-order-state-machine.jmx' 12 0 `
            $fingerprint $outputRoot $results $resultsPath
    }
    if (-not $NoWaveGaps.IsPresent) {
        Wait-ToBoundary $startedAt ([timespan]::FromMinutes(632)) 'W08' $statePath
    }

    & (Join-Path $PSScriptRoot 'Invoke-MembershipPaymentFinalDatabaseScan.ps1') `
        -SoakStartedAt $startedAt `
        -ExpectedOrderCount 148 `
        -OutputFile $finalDatabaseScanPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Local final PostgreSQL scan failed.' }

    & (Join-Path $PSScriptRoot 'Save-MembershipPaymentInfrastructureEvidence.ps1') `
        -Capture AFTER `
        -InspectionBaseUrl $baseUrl `
        -OrdersRoot $absoluteOutput `
        -OutputFile $infrastructureAfterPath
    if ($LASTEXITCODE -ne 0) { throw 'Local final infrastructure capture failed.' }
    & (Join-Path $PSScriptRoot 'Test-MembershipPaymentFinalInfrastructure.ps1') `
        -BeforeEvidence $infrastructureBeforePath `
        -AfterEvidence $infrastructureAfterPath `
        -ExpectedOrderCount 148 `
        -ExpectedPaymentDlqDelta 1 `
        -OutputFile $infrastructureVerdictPath
    if ($LASTEXITCODE -ne 0) { throw 'Local final infrastructure verification failed.' }

    $results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultsPath -Encoding UTF8
    [ordered]@{
        verdict = 'PASS'
        phase = 'LOCAL'
        soakId = $SoakId
        formalStartedAt = $startedAt.ToString('O')
        sourceFingerprint = $fingerprint
        actualOrdersExpected = 148
        finalDatabaseScan = $finalDatabaseScanPath
        finalInfrastructureScan = $infrastructureVerdictPath
        nextPhase = 'BAR'
        deploymentRequired = $true
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $deploymentGatePath -Encoding UTF8
    Save-State 'WAITING_FOR_DEPLOYMENT' 'DEPLOYMENT_GATE' $startedAt $startedAt.AddMinutes(662) $statePath
    Write-Host "LOCAL SOAK PASS. Deploy the identical fingerprint and continue with BAR phase."
    Write-Host "Deployment gate: $deploymentGatePath"
} catch {
    $results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultsPath -Encoding UTF8
    Save-State 'FAIL' 'STOPPED' $startedAt ([datetimeoffset]::UtcNow) $statePath
    throw
}
