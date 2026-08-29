[CmdletBinding()]
param(
    [string] $RunId = '',
    [ValidateRange(1, 65535)]
    [int] $Port = 6655,
    [AllowEmptyCollection()]
    [string[]] $PreviousScenarioOrdersCsvPath = @(),
    [switch] $PreviousScenarioListIsAuthoritative,
    [string] $PreviousComparableRunRoot = '',
    [string] $PostgresUrl = '',
    [ValidateSet(384)]
    [int] $PostgresMaxConnections = 384,
    [ValidateSet(256)]
    [int] $HikariMaximumPoolSize = 256,
    [ValidateSet(8)]
    [int] $HikariMinimumIdle = 8,
    [ValidateRange(0, 64)]
    [int] $MaximumNavicatConnections = 8,
    [ValidateSet(0, 120, 600)]
    [int] $PostgresStabilitySeconds = 120,
    [ValidateSet('PERFORMANCE_40K', 'CAPACITY_80K')]
    [string] $RunScale = 'PERFORMANCE_40K',
    [ValidateSet(64)]
    [int] $RedisWriteBatchSize = 64,
    [ValidateSet(6)]
    [int] $RedisWriteLaneCount = 6,
    [ValidateSet(384)]
    [int] $RedisWriteMaximumInflight = 384,
    [ValidateSet('E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR')]
    [string] $StartGroupCode = 'E-P1',
    [switch] $SkipInitialGates,
    [switch] $DirectConcurrencyCanary,
    [switch] $KeepApplicationRunningAfterSuite,
    [switch] $ReuseExistingApplication,
    [ValidateRange(0, 2147483647)]
    [int] $ExistingApplicationPid = 0,
    [string] $ExistingApplicationDescriptorPath = '',
    [ValidateSet(0, 1, 8)]
    [int] $ExpectedFormalSegmentCount = 0,
    [string] $MasterRunId = '',
    [ValidatePattern('^[A-Za-z0-9_-]{1,128}$')]
    [string] $GoldenBaselineRunId = 'membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048',
    [string] $GoldenBaselineEvidenceRoot = '',
    [string] $ExistingPostgresStabilityGatePath = '',
    [switch] $StopAfterWarmupSequence
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# PowerShell 跨进程参数绑定会把单元素 string[] 退化为标量；先恢复数组语义，避免 StrictMode 下读取 Count 失败。
$PreviousScenarioOrdersCsvPath = @($PreviousScenarioOrdersCsvPath)

if ($HikariMinimumIdle -gt $HikariMaximumPoolSize) {
    throw 'Hikari minimum idle cannot exceed maximum pool size.'
}
if (($HikariMaximumPoolSize + $MaximumNavicatConnections + 2) -ge
        $PostgresMaxConnections) {
    throw 'The PostgreSQL connection contract does not reserve sampler and management headroom.'
}
if ($ReuseExistingApplication -and ($ExistingApplicationPid -le 0 -or
        [string]::IsNullOrWhiteSpace($ExistingApplicationDescriptorPath))) {
    throw 'Application reuse requires an exact PID and its first-stage descriptor.'
}
if (-not $ReuseExistingApplication -and ($ExistingApplicationPid -ne 0 -or
        -not [string]::IsNullOrWhiteSpace($ExistingApplicationDescriptorPath))) {
    throw 'An existing application descriptor is only valid in explicit reuse mode.'
}
if ($StopAfterWarmupSequence -and -not $DirectConcurrencyCanary) {
    throw 'Warmup-only stop mode is restricted to the E-P1 direct-concurrency Canary.'
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$fixedJarSha256 = 'b3c924c4abf49266957b9f93076fa2268e5c1e7e447899a1411eff16375ac597'
if ([string]::IsNullOrWhiteSpace($GoldenBaselineEvidenceRoot)) {
    $GoldenBaselineEvidenceRoot = Join-Path $repositoryRoot `
        "loadtest-output\soak\$GoldenBaselineRunId\millisecond-boundary"
}
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = 'membership-order-create-' + $RunScale.ToLowerInvariant() +
        "-redis$RedisWriteBatchSize-lane$RedisWriteLaneCount-hikari256-pg384-" +
        (Get-Date -Format 'yyyyMMdd-HHmmss')
}
if ($RunId -notmatch '^[A-Za-z0-9_-]{1,128}$') {
    throw 'RunId may contain only letters, digits, underscore and hyphen.'
}
$runRoot = Join-Path $repositoryRoot `
    "loadtest-output\soak\$RunId\millisecond-boundary"
if (Test-Path -LiteralPath $runRoot) {
    throw "The formal run directory already exists: $runRoot"
}
New-Item -ItemType Directory -Path $runRoot | Out-Null

$defaultPreviousRoot = Join-Path $repositoryRoot `
    'loadtest-output\soak\membership-lua-refund-focused-retest2-20260824-130724\millisecond-boundary'
if ($PreviousScenarioOrdersCsvPath.Count -eq 0 -and
        -not $PreviousScenarioListIsAuthoritative) {
    $PreviousScenarioOrdersCsvPath = @(
        'E-P1','E-PR','E-A1','E-AR','H-P1','H-PR','H-A1' |
            ForEach-Object { Join-Path $defaultPreviousRoot "$_\scenario-orders.csv" })
}
if ([string]::IsNullOrWhiteSpace($PreviousComparableRunRoot)) {
    $PreviousComparableRunRoot = Join-Path $repositoryRoot `
        'loadtest-output\soak\membership-payment-256-40k-retest-20260824-170500\millisecond-boundary'
}

$sampler = $null
$suite = $null
$applicationPid = $null
$application = $null
$stopPath = Join-Path $runRoot 'evidence-sampler.stop'
$failurePath = Join-Path $runRoot 'orchestrator-failure.json'
$suiteConfigurationPath = Join-Path $runRoot 'suite-child-configuration.json'
$suiteStdoutPath = Join-Path $runRoot 'suite-runner.stdout.log'
$suiteStderrPath = Join-Path $runRoot 'suite-runner.stderr.log'
$samplerStdoutPath = Join-Path $runRoot 'evidence-sampler.stdout.log'
$samplerStderrPath = Join-Path $runRoot 'evidence-sampler.stderr.log'
$orchestratorFailure = $null
$structuredSuiteFailure = $null
$keepApplicationOnExit = $false
$runStatePath = Join-Path $runRoot 'run-state.json'
$heartbeatPath = Join-Path $runRoot 'heartbeat.json'
$expectedRowsPerSegment = if ($RunScale -eq 'PERFORMANCE_40K') { 5000 } else { 10000 }
$allSegments = @('E-P1','E-PR','E-A1','E-AR','H-P1','H-PR','H-A1','H-AR')
$segments = @(if ($DirectConcurrencyCanary) {
    @('E-P1')
} else {
    $startIndex = [Array]::IndexOf($allSegments, $StartGroupCode)
    @($allSegments[$startIndex..($allSegments.Count - 1)])
})
$resolvedExpectedFormalSegmentCount = if ($ExpectedFormalSegmentCount -eq 0) {
    $segments.Count
} else { $ExpectedFormalSegmentCount }
if ($resolvedExpectedFormalSegmentCount -ne $segments.Count) {
    throw "Expected formal segment count does not match the selected Suite: expected=$resolvedExpectedFormalSegmentCount actual=$($segments.Count)"
}
$expectedRunOrders = $expectedRowsPerSegment * $segments.Count
if ($DirectConcurrencyCanary -and $RunScale -ne 'PERFORMANCE_40K') {
    throw 'Direct concurrency canary is fixed to PERFORMANCE_40K E-P1.'
}
if ($DirectConcurrencyCanary -and $StartGroupCode -ne 'E-P1') {
    throw 'Direct concurrency canary cannot resume from a later group.'
}
if ($SkipInitialGates -and $StartGroupCode -eq 'E-P1') {
    throw 'Initial gates may only be skipped when resuming after an evidenced group.'
}
if (-not $SkipInitialGates -and [string]::IsNullOrWhiteSpace($ExistingPostgresStabilityGatePath) -and
        $PostgresStabilitySeconds -notin @(120, 600)) {
    throw 'A formal run requires a 120-second or explicitly extended 600-second PostgreSQL stability gate.'
}
if (-not [string]::IsNullOrWhiteSpace($ExistingPostgresStabilityGatePath) -and
        $PostgresStabilitySeconds -ne 0) {
    throw 'A reused PostgreSQL gate must set PostgresStabilitySeconds to zero.'
}

function Write-AtomicJson([string] $Path, [object] $Value) {
    $temporaryPath = "$Path.$PID.$([guid]::NewGuid().ToString('N')).partial"
    try {
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
                # PowerShell 会包装底层文件异常，遍历异常链后仅重试 Windows 共享、锁和拒绝访问冲突。
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

function Save-RunState([string] $Phase, [string] $Status) {
    $progress = Get-SuiteProgress
    Write-AtomicJson -Path $runStatePath -Value ([ordered]@{
        masterRunId = if ([string]::IsNullOrWhiteSpace($MasterRunId)) { $null } else { $MasterRunId }
        runId = $RunId
        runScale = $RunScale
        phase = $Phase
        status = $Status
        orchestratorPid = $PID
        applicationPid = $applicationPid
        samplerPid = if ($null -eq $sampler) { $null } else { $sampler.Id }
        suitePid = if ($null -eq $suite) { $null } else { $suite.Id }
        currentGroupCode = $progress.groupCode
        warmupAttempt = $progress.warmupAttempt
        suiteState = $progress.state
        suiteWave = $progress.wave
        sourceFingerprint = if (Test-Path -LiteralPath (Join-Path $runRoot 'formal-preflight.json')) {
            (Get-Content -Raw -LiteralPath (Join-Path $runRoot 'formal-preflight.json') |
                ConvertFrom-Json).source.gitDiffSha256
        } else { $null }
        updatedAt = [datetimeoffset]::UtcNow.ToString('O')
    })
}

function Save-Heartbeat([string] $Phase) {
    $progress = Get-SuiteProgress
    Write-AtomicJson -Path $heartbeatPath -Value ([ordered]@{
        masterRunId = if ([string]::IsNullOrWhiteSpace($MasterRunId)) { $null } else { $MasterRunId }
        runId = $RunId
        phase = $Phase
        orchestratorPid = $PID
        applicationPid = $applicationPid
        samplerPid = if ($null -eq $sampler) { $null } else { $sampler.Id }
        suitePid = if ($null -eq $suite) { $null } else { $suite.Id }
        currentGroupCode = $progress.groupCode
        warmupAttempt = $progress.warmupAttempt
        suiteState = $progress.state
        suiteWave = $progress.wave
        sampledAt = [datetimeoffset]::UtcNow.ToString('O')
    })
}

function Get-SuiteProgress {
    $stateFile = Join-Path $runRoot 'soak-state.json'
    if (-not (Test-Path -LiteralPath $stateFile -PathType Leaf)) {
        return [pscustomobject]@{ state=$null; wave=$null; groupCode=$null; warmupAttempt=$null }
    }
    try {
        $state = Read-JsonSnapshot -Path $stateFile
        $wave = [string]$state.wave
        $groupCode = $null
        $warmupAttempt = $null
        if ($wave -match '^(?<group>E-P1|E-PR|E-A1|E-AR|H-P1|H-PR|H-A1|H-AR)(/attempt-(?<attempt>[12]))?$') {
            $groupCode = $Matches.group
            if ($Matches.ContainsKey('attempt') -and
                    -not [string]::IsNullOrWhiteSpace([string]$Matches['attempt'])) {
                $warmupAttempt = [int]$Matches['attempt']
            }
        }
        return [pscustomobject]@{
            state = [string]$state.state
            wave = $wave
            groupCode = $groupCode
            warmupAttempt = $warmupAttempt
        }
    } catch {
        return [pscustomobject]@{ state='UNREADABLE'; wave=$null; groupCode=$null; warmupAttempt=$null }
    }
}

function Resolve-PostgresUrl {
    if (-not [string]::IsNullOrWhiteSpace($PostgresUrl)) { return $PostgresUrl }
    if ($env:MEMBERSHIP_PAYMENT_POSTGRES_URL) {
        return $env:MEMBERSHIP_PAYMENT_POSTGRES_URL
    }
    if ($env:POSTGRES_URL) { return $env:POSTGRES_URL }
    return 'postgresql://postgres@127.0.0.1:5431/ai_temperate'
}

function Invoke-PsqlText([string] $Sql, [switch] $Aligned) {
    $arguments = @('-w', (Resolve-PostgresUrl), '-X', '-v', 'ON_ERROR_STOP=1')
    if (-not $Aligned) { $arguments += @('-A', '-t', '-F', '|') }
    $lines = @(& psql @arguments -c $Sql 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL preflight command failed: $($lines -join ' ')"
    }
    return $lines -join "`n"
}

function Get-TextSha256([string] $Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Get-SourceFacts {
    Push-Location $repositoryRoot
    try {
        $paths = @(
            'ai-temperate-common','ai-temperate-model','ai-temperate-mapper',
            'ai-temperate-service','ai-temperate-web','loadtest','sql','docs','pom.xml')
        $head = (& git rev-parse HEAD 2>$null).Trim()
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
            gitHead = $head
            gitStatusSha256 = Get-TextSha256 $status
            gitDiffSha256 = Get-TextSha256 $diff
            untrackedSourceSha256 = Get-TextSha256 ($untrackedFacts -join "`n")
        }
    } finally {
        Pop-Location
    }
}

function Assert-SourceAndJarUnchanged(
        [object] $ExpectedSource,
        [string] $JarPath,
        [string] $ExpectedJarSha256) {
    $actualSource = Get-SourceFacts
    $actualJarSha256 = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if (($actualSource | ConvertTo-Json -Compress) -ne
            ($ExpectedSource | ConvertTo-Json -Compress) -or
            $actualJarSha256 -ne $ExpectedJarSha256) {
        throw 'Source, worktree or packaged JAR drifted during the formal run.'
    }
}

function Assert-FormalEnvironment {
    foreach ($command in @('git','java','jcmd','jmeter','psql','docker','pwsh')) {
        [void](Get-Command $command -ErrorAction Stop)
    }
    $conflictingJava = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object {
            $_.CommandLine -match 'AiTemperateApplication|ai-temperate-web-0\.0\.1-SNAPSHOT\.jar|surefire|junit|ApacheJMeter'
        })
    $unexpectedJava = @(if ($ReuseExistingApplication) {
        @($conflictingJava | Where-Object { [int]$_.ProcessId -ne $ExistingApplicationPid })
    } else {
        $conflictingJava
    })
    if ($unexpectedJava.Count -ne 0 -or
            ($ReuseExistingApplication -and
                @($conflictingJava | Where-Object { [int]$_.ProcessId -eq $ExistingApplicationPid }).Count -ne 1)) {
        throw 'A conflicting application, test JVM or JMeter process is already running.'
    }
    $databaseFacts = (Invoke-PsqlText @'
SELECT current_setting('max_connections'),
       COUNT(*) FILTER (
           WHERE backend_type = 'client backend'
             AND pid <> pg_backend_pid()),
       COUNT(*) FILTER (
           WHERE backend_type = 'client backend'
             AND pid <> pg_backend_pid()
             AND application_name = 'Navicat'),
       COUNT(*) FILTER (
           WHERE backend_type = 'client backend'
             AND pid <> pg_backend_pid()
             AND application_name = 'Navicat'
             AND state = 'active'),
       COUNT(*) FILTER (
           WHERE backend_type = 'client backend'
             AND pid <> pg_backend_pid()
             AND application_name = 'Navicat'
             AND state = 'idle in transaction'),
       COUNT(*) FILTER (
           WHERE backend_type = 'client backend'
             AND pid <> pg_backend_pid()
             AND application_name = 'Navicat'
             AND query ~* '^[[:space:]]*(insert|update|delete|merge|create|alter|drop|truncate|vacuum|analyze|grant|revoke|lock|copy)[[:space:]]'),
       COUNT(*) FILTER (
           WHERE backend_type = 'client backend'
             AND pid <> pg_backend_pid()
             AND application_name IS DISTINCT FROM 'Navicat')
FROM pg_stat_activity;
'@).Trim().Split('|')
    if ($databaseFacts.Count -ne 7 -or
            [int]$databaseFacts[0] -ne $PostgresMaxConnections) {
        throw "PostgreSQL max_connections is not exactly $PostgresMaxConnections."
    }
    if ([int]$databaseFacts[2] -gt $MaximumNavicatConnections) {
        throw 'Navicat observer connections exceed the declared budget.'
    }
    if ([int]$databaseFacts[4] -ne 0) {
        throw 'Navicat contains an idle transaction before the formal run.'
    }
    if ([int]$databaseFacts[5] -ne 0) {
        throw 'Navicat most recently executed a write or DDL statement.'
    }
    if (-not $ReuseExistingApplication -and [int]$databaseFacts[6] -ne 0) {
        throw 'An undeclared PostgreSQL client connection is active before the formal run.'
    }
    [ordered]@{
        capturedAt = [datetimeoffset]::UtcNow.ToString('O')
        maximumAllowed = $MaximumNavicatConnections
        totalClientConnections = [int]$databaseFacts[1]
        navicatTotal = [int]$databaseFacts[2]
        navicatActive = [int]$databaseFacts[3]
        navicatIdleInTransaction = [int]$databaseFacts[4]
        navicatWriteOrDdl = [int]$databaseFacts[5]
        undeclaredConnections = [int]$databaseFacts[6]
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (
        Join-Path $runRoot 'navicat-observer-baseline.json') -Encoding UTF8
    $indexDefinition = Invoke-PsqlText @'
SELECT indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'membership_order'
  AND indexname = 'idx_membership_order_latest_paid';
'@
    if ($indexDefinition -notmatch 'login_identity_id.*membership_tier.*paid_at DESC NULLS LAST.*created_at DESC.*id DESC' -or
            $indexDefinition -notmatch 'WHERE \(status = 2\)') {
        throw 'The latest PAID partial index definition does not match the frozen contract.'
    }
}

function Validate-ReusedApplication {
    $descriptorPath = (Resolve-Path -LiteralPath $ExistingApplicationDescriptorPath).Path
    $descriptor = Get-Content -Raw -LiteralPath $descriptorPath | ConvertFrom-Json
    if ([int]$descriptor.pid -ne $ExistingApplicationPid) {
        throw 'The reused application descriptor does not own the requested PID.'
    }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ExistingApplicationPid" `
        -ErrorAction SilentlyContinue
    if ($null -eq $process -or
            $process.CommandLine -notmatch 'ai-temperate-web-0\.0\.1-SNAPSHOT\.jar') {
        throw 'TEST_INVALID_APPLICATION: the reused application PID is absent or is not the frozen loadtest JAR.'
    }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $ExistingApplicationPid)
    if ($listeners.Count -ne 1) {
        throw 'TEST_INVALID_APPLICATION: the reused application does not own exactly one loopback test listener.'
    }
    [void](Wait-ApplicationReady -ProcessId $ExistingApplicationPid)
    $runtime = Invoke-RestMethod -Method Get `
        -Uri "http://127.0.0.1:$Port/internal/test/membership-payments/loadtest-inspection/runtime" `
        -TimeoutSec 15
    if (-not [bool]$runtime.hikari.poolAvailable -or
            [int]$runtime.hikari.configuredMaximumPoolSize -ne $HikariMaximumPoolSize -or
            [int]$runtime.hikari.configuredMinimumIdle -ne $HikariMinimumIdle -or
            [int]$runtime.redisWrite.configuredBatchSize -ne $RedisWriteBatchSize -or
            [int]$runtime.redisWrite.configuredLaneCount -ne $RedisWriteLaneCount -or
            [int]$runtime.redisWrite.maximumInflight -ne $RedisWriteMaximumInflight) {
        throw 'The reused application runtime configuration drifted from the frozen contract.'
    }
    return $descriptor
}

function Assert-ApplicationIdentity([int] $ProcessId) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" `
        -ErrorAction SilentlyContinue
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $ProcessId)
    if ($null -eq $process -or
            $process.CommandLine -notmatch 'ai-temperate-web-0\.0\.1-SNAPSHOT\.jar' -or
            $listeners.Count -ne 1) {
        throw 'TEST_INVALID_APPLICATION: application PID, JAR identity or listener ownership changed during the run.'
    }
}

function Wait-ApplicationReady([int] $ProcessId) {
    $deadline = [datetimeoffset]::UtcNow.AddSeconds(180)
    while ([datetimeoffset]::UtcNow -lt $deadline) {
        if ($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            throw 'The loadtest application exited before the runtime endpoint became ready.'
        }
        try {
            # 只读 fixture state 会先完成 DataSource 初始化，避免把 Hikari 尚未建池误判为配置漂移。
            [void](Invoke-RestMethod -Method Get -TimeoutSec 5 `
                -Uri "http://127.0.0.1:$Port/internal/test/membership-payments/millisecond-boundary/state")
            $runtime = Invoke-RestMethod -Method Get -TimeoutSec 5 `
                -Uri "http://127.0.0.1:$Port/internal/test/membership-payments/loadtest-inspection/runtime"
            if ([int]$runtime.hikari.configuredMaximumPoolSize -ne
                        $HikariMaximumPoolSize -or
                    [int]$runtime.hikari.configuredMinimumIdle -ne
                        $HikariMinimumIdle) {
                throw "Hikari runtime configuration is not the fixed $HikariMaximumPoolSize/$HikariMinimumIdle contract."
            }
            if ([bool]$runtime.hikari.poolAvailable) {
                return $runtime
            }
        } catch {
            if ($_.Exception.Message -match 'Hikari runtime configuration is not') { throw }
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'The loadtest application did not become ready within 180 seconds.'
}

function Wait-EvidenceSamplerReady(
        [System.Diagnostics.Process] $SamplerProcess) {
    $requiredPaths = @(
        (Join-Path $runRoot 'scheduler-queue-samples.csv'),
        (Join-Path $runRoot 'hikari-runtime-samples.csv'),
        (Join-Path $runRoot 'redis-write-runtime-samples.csv'),
        (Join-Path $runRoot 'host-runtime-samples.csv'),
        (Join-Path $runRoot 'postgres-connection-samples.raw.txt'))
    $deadline = [datetimeoffset]::UtcNow.AddSeconds(45)
    while ([datetimeoffset]::UtcNow -lt $deadline) {
        if ($SamplerProcess.HasExited) {
            throw 'The evidence sampler exited before its protected baseline was ready.'
        }
        $ready = @($requiredPaths | Where-Object {
            (Test-Path -LiteralPath $_ -PathType Leaf) -and
                (Get-Item -LiteralPath $_).Length -gt 0L
        }).Count -eq $requiredPaths.Count
        if ($ready) { return }
        Start-Sleep -Milliseconds 250
    }
    throw 'The protected evidence sampler baseline was not ready within 45 seconds.'
}

function Save-IndexStatistics([string] $Path) {
    $sql = @'
SELECT indexrelname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
  AND relname = 'membership_order'
  AND indexrelname = 'idx_membership_order_latest_paid';
'@
    Invoke-PsqlText -Sql $sql -Aligned |
        Set-Content -LiteralPath $Path -Encoding UTF8
}

function Stop-OwnedApplication {
    if ($null -eq $applicationPid) { return }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$applicationPid" `
        -ErrorAction SilentlyContinue
    if ($null -ne $process -and
            $process.CommandLine -match 'ai-temperate-web-0\.0\.1-SNAPSHOT\.jar') {
        Stop-Process -Id $applicationPid -ErrorAction SilentlyContinue
        Wait-Process -Id $applicationPid -Timeout 20 -ErrorAction SilentlyContinue
    }
}

function Archive-OwnedFormalLogs {
    if ($null -eq $applicationPid) { return }
    $logFacts = [Collections.Generic.List[object]]::new()
    foreach ($entry in @(
            @{ source = Join-Path $repositoryRoot 'logs\membership-payment-state-machine.log'; archive = 'raw-membership-payment-state-machine.log' },
            @{ source = Join-Path $repositoryRoot 'logs\membership-order-create-http-events.log'; archive = 'raw-membership-order-create-http-events.log' })) {
        $source = [IO.Path]::GetFullPath([string]$entry.source)
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $archive = Join-Path $runRoot ([string]$entry.archive)
        Copy-Item -LiteralPath $source -Destination $archive -Force
        $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        $archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        if ($sourceHash -cne $archiveHash) {
            throw "Formal log archive hash mismatch: $source"
        }
        $logFacts.Add([pscustomobject]@{
            source = $source
            archive = $archive
            bytes = (Get-Item -LiteralPath $archive).Length
            sha256 = $archiveHash.ToLowerInvariant()
        })
        # 应用已停止且归档哈希相同后才删除固定路径，使下一轮必须从空日志开始。
        Remove-Item -LiteralPath $source -Force
    }
    Write-AtomicJson -Path (Join-Path $runRoot 'formal-log-archive-manifest.json') `
        -Value ([ordered]@{
            runId = $RunId
            archivedAt = [datetimeoffset]::UtcNow.ToString('O')
            files = @($logFacts)
        })
}

function Stop-OwnedSuiteTree {
    if ($null -eq $suite) { return }
    $owned = [Collections.Generic.List[int]]::new()
    $frontier = [Collections.Generic.List[int]]::new()
    $frontier.Add([int]$suite.Id)
    while ($frontier.Count -gt 0) {
        $parent = $frontier[0]
        $frontier.RemoveAt(0)
        foreach ($child in @(Get-CimInstance Win32_Process `
                -Filter "ParentProcessId=$parent" -ErrorAction SilentlyContinue)) {
            $owned.Add([int]$child.ProcessId)
            $frontier.Add([int]$child.ProcessId)
        }
    }
    for ($ownedIndex = $owned.Count - 1; $ownedIndex -ge 0; $ownedIndex--) {
        $ownedProcessId = $owned[$ownedIndex]
        Stop-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $suite.Id -ErrorAction SilentlyContinue
}

try {
    Save-RunState -Phase 'PREFLIGHT' -Status 'RUNNING'
    Save-Heartbeat -Phase 'PREFLIGHT'
    Assert-FormalEnvironment
    foreach ($path in $PreviousScenarioOrdersCsvPath) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Previous scenario manifest is missing: $path"
        }
    }
    $jarPath = (Resolve-Path (Join-Path $repositoryRoot `
        'ai-temperate-web\target\ai-temperate-web-0.0.1-SNAPSHOT.jar')).Path
    $actualJarSha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualJarSha256 -ne $fixedJarSha256) {
        throw "TEST_INVALID_ARTIFACT: fixed JAR SHA-256 mismatch: $actualJarSha256"
    }
    $sourceFacts = Get-SourceFacts
    $preflight = [ordered]@{
        runId = $RunId
        capturedAt = [datetimeoffset]::UtcNow.ToString('O')
        configuration = [ordered]@{
            runScale = $RunScale
            orders = $expectedRunOrders
            rowsPerSegment = $expectedRowsPerSegment
            segments = $segments
            creationConcurrency = 256
            paymentConcurrency = 56
            callbackConcurrency = 256
            callbackWorker = '100x50'
            orderPersistWorker = '100x50'
            rabbitConsumers = '48/48'
            hikari = "$HikariMaximumPoolSize/$HikariMinimumIdle"
            hikariMaximumPoolSize = $HikariMaximumPoolSize
            hikariMinimumIdle = $HikariMinimumIdle
            postgresMaxConnections = $PostgresMaxConnections
            maximumNavicatConnections = $MaximumNavicatConnections
            postgresStabilitySeconds = $PostgresStabilitySeconds
            redisWriteBatchSize = $RedisWriteBatchSize
            redisWriteLaneCount = $RedisWriteLaneCount
            redisWriteMaximumInflight = $RedisWriteMaximumInflight
            directConcurrencyCanary = [bool]$DirectConcurrencyCanary
            expectedFormalSegmentCount = $resolvedExpectedFormalSegmentCount
            masterRunId = $MasterRunId
            goldenBaselineRunId = $GoldenBaselineRunId
            goldenBaselineEvidenceRoot = [IO.Path]::GetFullPath($GoldenBaselineEvidenceRoot)
            existingPostgresStabilityGatePath = $ExistingPostgresStabilityGatePath
            stopAfterWarmupSequence = [bool]$StopAfterWarmupSequence
            startGroupCode = $StartGroupCode
            skipInitialGates = [bool]$SkipInitialGates
            reuseExistingApplication = [bool]$ReuseExistingApplication
            existingApplicationPid = if ($ReuseExistingApplication) { $ExistingApplicationPid } else { $null }
            provider = 'LOCAL_SIMULATOR'
        }
        source = $sourceFacts
        jarSha256 = $actualJarSha256
    }
    $preflight | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath (Join-Path $runRoot 'formal-preflight.json') -Encoding UTF8
    Save-IndexStatistics (Join-Path $runRoot 'latest-paid-index-before.txt')

    if ($ReuseExistingApplication) {
        $application = Validate-ReusedApplication
        $applicationPid = $ExistingApplicationPid
    } else {
        $startupJson = & (Join-Path $PSScriptRoot `
            'Start-MembershipLoadtestApplication.ps1') `
            -Port $Port -RunId $RunId -EnableMillisecondBoundary `
            -PostgresPoolMaximumSize $HikariMaximumPoolSize `
            -PostgresPoolMinimumIdle $HikariMinimumIdle `
            -RedisWriteBatchSize $RedisWriteBatchSize `
            -RedisWriteLaneCount $RedisWriteLaneCount `
            -RedisWriteMaximumInflight $RedisWriteMaximumInflight
        $application = ($startupJson -join "`n") | ConvertFrom-Json
        $applicationPid = [int]$application.pid
    }
    $application | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $runRoot 'application-start.json') -Encoding UTF8
    Save-RunState -Phase 'APPLICATION_START' -Status 'RUNNING'
    Save-Heartbeat -Phase 'APPLICATION_START'
    [void](Wait-ApplicationReady -ProcessId $applicationPid)
    Assert-ApplicationIdentity -ProcessId $applicationPid

    & jcmd $applicationPid Thread.print 2>&1 |
        Set-Content -LiteralPath (Join-Path $runRoot `
            'membership-payment-thread-dump.txt') -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw 'The formal thread dump failed.' }
    $threadDump = Get-Content -Raw -LiteralPath (Join-Path $runRoot `
        'membership-payment-thread-dump.txt')
    if ($threadDump -notmatch 'membership-payment-callback-' -or
            $threadDump -notmatch 'membership-payment-order-persist-') {
        throw 'The two independent membership scheduler threads were not observed.'
    }

    $samplerArguments = @(
        '-NoProfile','-File',
        (Join-Path $PSScriptRoot 'Measure-MembershipPaymentRuntimeEvidence.ps1'),
        '-RunId',$RunId,'-AppPid',[string]$applicationPid,'-Port',[string]$Port,
        '-PostgresUrl',(Resolve-PostgresUrl),
        '-PostgresMaxConnections',[string]$PostgresMaxConnections,
        '-MaximumNavicatConnections',[string]$MaximumNavicatConnections,
        '-HikariMaximumPoolSize',[string]$HikariMaximumPoolSize,
        '-HikariMinimumIdle',[string]$HikariMinimumIdle,
        '-RedisWriteBatchSize',[string]$RedisWriteBatchSize,
        '-RedisWriteLaneCount',[string]$RedisWriteLaneCount,
        '-RedisWriteMaximumInflight',[string]$RedisWriteMaximumInflight)
    $sampler = Start-Process -FilePath (Get-Command pwsh).Source `
        -ArgumentList $samplerArguments -WorkingDirectory $repositoryRoot `
        -RedirectStandardOutput $samplerStdoutPath `
        -RedirectStandardError $samplerStderrPath `
        -WindowStyle Hidden -PassThru
    Wait-EvidenceSamplerReady -SamplerProcess $sampler
    Save-RunState -Phase 'SAMPLING' -Status 'RUNNING'
    Save-Heartbeat -Phase 'SAMPLING'

    [ordered]@{
        runId = $RunId
        port = $Port
        previousScenarioOrdersCsvPath = @($PreviousScenarioOrdersCsvPath)
        postgresMaxConnections = $PostgresMaxConnections
        hikariMaximumPoolSize = $HikariMaximumPoolSize
        hikariMinimumIdle = $HikariMinimumIdle
        maximumNavicatConnections = $MaximumNavicatConnections
        postgresStabilitySeconds = $PostgresStabilitySeconds
        runScale = $RunScale
        redisWriteBatchSize = $RedisWriteBatchSize
        redisWriteLaneCount = $RedisWriteLaneCount
        redisWriteMaximumInflight = $RedisWriteMaximumInflight
        directConcurrencyCanary = [bool]$DirectConcurrencyCanary
        goldenBaselineRunId = $GoldenBaselineRunId
        goldenBaselineEvidenceRoot = [IO.Path]::GetFullPath($GoldenBaselineEvidenceRoot)
        existingPostgresStabilityGatePath = $ExistingPostgresStabilityGatePath
        timingLogRunId = [string]$application.runId
        stopAfterWarmupSequence = [bool]$StopAfterWarmupSequence
        startGroupCode = $StartGroupCode
        skipInitialGates = [bool]$SkipInitialGates
        reuseExistingApplication = [bool]$ReuseExistingApplication
        existingApplicationPid = if ($ReuseExistingApplication) { $ExistingApplicationPid } else { $null }
    } | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $suiteConfigurationPath -Encoding UTF8
    $suite = Start-Process -FilePath (Get-Command pwsh).Source `
        -ArgumentList @(
            '-NoProfile','-File',
            (Join-Path $PSScriptRoot 'Invoke-MembershipMillisecondBoundarySuiteChild.ps1'),
            '-ConfigurationPath',$suiteConfigurationPath) `
        -WorkingDirectory $repositoryRoot `
        -RedirectStandardOutput $suiteStdoutPath `
        -RedirectStandardError $suiteStderrPath `
        -WindowStyle Hidden -PassThru
    Save-RunState -Phase 'FORMAL_SUITE' -Status 'RUNNING'
    Save-Heartbeat -Phase 'FORMAL_SUITE'

    while (-not $suite.HasExited) {
        Assert-ApplicationIdentity -ProcessId $applicationPid
        if ($sampler.HasExited) {
            Stop-OwnedApplication
            throw 'The runtime evidence sampler exited before the formal Suite completed.'
        }
        $queueEvidencePath = Join-Path $runRoot 'scheduler-queue-samples.csv'
        $allowedEvidenceGapSeconds = 5D
        $suiteStatePath = Join-Path $runRoot 'soak-state.json'
        if (Test-Path -LiteralPath $suiteStatePath -PathType Leaf) {
            $suiteState = Read-JsonSnapshot -Path $suiteStatePath
            # Windows 空载稳定性观察偶尔会让全部采样进程同时停顿约十秒；正式预检和负载阶段仍保留五秒硬门禁。
            if ([string]$suiteState.state -eq 'PRECHECK' -and
                    [string]$suiteState.wave -eq 'POSTGRES_STABILITY') {
                $allowedEvidenceGapSeconds = 15D
            }
        }
        if ((Test-Path -LiteralPath $queueEvidencePath -PathType Leaf) -and
                ((Get-Date) - (Get-Item -LiteralPath $queueEvidencePath).LastWriteTime).TotalSeconds -gt
                    $allowedEvidenceGapSeconds) {
            throw "The runtime evidence file stopped growing for more than $allowedEvidenceGapSeconds seconds."
        }
        $hikariEvidencePath = Join-Path $runRoot 'hikari-runtime-samples.csv'
        if (Test-Path -LiteralPath $hikariEvidencePath -PathType Leaf) {
            $hikariRows = @(Import-Csv -LiteralPath $hikariEvidencePath)
            if ($hikariRows.Count -gt 0) {
                $latestHikari = $hikariRows[$hikariRows.Count - 1]
                if (-not [bool]::Parse([string]$latestHikari.poolAvailable) -or
                        [int]$latestHikari.configuredMaximumPoolSize -ne 256 -or
                        [int]$latestHikari.configuredMinimumIdle -ne 8 -or
                        [double]$latestHikari.timeoutCount -gt 0D) {
                    throw 'RELIABILITY_FAILURE: Hikari configuration, availability or timeout contract failed.'
                }
            }
        }
        $postgresEvidencePath = Join-Path $runRoot 'postgres-connection-samples.csv'
        if (Test-Path -LiteralPath $postgresEvidencePath -PathType Leaf) {
            $postgresRows = @(Import-Csv -LiteralPath $postgresEvidencePath)
            if ($postgresRows.Count -gt 0 -and
                    [int]$postgresRows[$postgresRows.Count - 1].totalConnections -ge 384) {
                throw 'RELIABILITY_FAILURE: PostgreSQL connections reached max_connections=384.'
            }
        }
        Save-Heartbeat -Phase 'FORMAL_SUITE'
        Start-Sleep -Seconds 2
        $suite.Refresh()
        $sampler.Refresh()
    }
    if ($suite.ExitCode -ne 0) {
        $suiteVerdictPath = Join-Path $runRoot 'verdict.json'
        if (Test-Path -LiteralPath $suiteVerdictPath -PathType Leaf) {
            try {
                $structuredSuiteFailure = Get-Content -Raw -LiteralPath $suiteVerdictPath |
                    ConvertFrom-Json
            } catch {
                $structuredSuiteFailure = $null
            }
        }
        if ($null -ne $structuredSuiteFailure -and
                $null -ne $structuredSuiteFailure.PSObject.Properties['primaryMessage']) {
            $suiteFailureCode = if ($null -ne
                    $structuredSuiteFailure.PSObject.Properties['failureCode']) {
                [string]$structuredSuiteFailure.failureCode
            } else { 'MILLISECOND_BOUNDARY_SUITE_FAILURE' }
            throw "${suiteFailureCode}: $([string]$structuredSuiteFailure.primaryMessage)"
        }
        throw "MILLISECOND_BOUNDARY_SUITE_EXIT_FAILURE: formal $RunScale Suite exited with code $($suite.ExitCode); diagnostics: $suiteStderrPath"
    }
    if ($sampler.HasExited) {
        throw 'The runtime evidence sampler exited before post-run evidence collection.'
    }
    Assert-ApplicationIdentity -ProcessId $applicationPid

    if ($StopAfterWarmupSequence) {
        $warmupOnlyCompletionPath = Join-Path $runRoot 'warmup-only-completion.json'
        if (-not (Test-Path -LiteralPath $warmupOnlyCompletionPath -PathType Leaf)) {
            throw 'Warmup-only Suite exited without its exact completion evidence.'
        }
        $warmupOnlyCompletion = Get-Content -Raw -LiteralPath $warmupOnlyCompletionPath |
            ConvertFrom-Json
        if ([string]$warmupOnlyCompletion.verdict -ne 'PASS' -or
                [int]$warmupOnlyCompletion.completedWarmupAttempt -ne 2 -or
                [bool]$warmupOnlyCompletion.formalExecuted) {
            throw 'Warmup-only completion evidence is invalid or reports formal execution.'
        }
        'stop' | Set-Content -LiteralPath $stopPath -Encoding ascii
        if (-not $sampler.WaitForExit(30000)) {
            Stop-Process -Id $sampler.Id -ErrorAction SilentlyContinue
            throw 'The runtime evidence sampler did not stop after warmup-only completion.'
        }
        if ($sampler.ExitCode -ne 0 -or
                (Test-Path -LiteralPath (Join-Path $runRoot `
                    'evidence-sampler-failure.json'))) {
            throw 'The runtime evidence sampler failed during warmup-only execution.'
        }
        Assert-SourceAndJarUnchanged `
            -ExpectedSource $sourceFacts `
            -JarPath $jarPath `
            -ExpectedJarSha256 ([string]$preflight.jarSha256)
        Save-RunState -Phase 'WARMUP_ONLY_COMPLETE' -Status 'PASS'
        Save-Heartbeat -Phase 'WARMUP_ONLY_COMPLETE'
        Get-Content -Raw -LiteralPath $warmupOnlyCompletionPath
        return
    }

    $probeFailure = $null
    if (-not $DirectConcurrencyCanary) {
        try {
            & (Join-Path $PSScriptRoot 'Invoke-MembershipLatestPaidIndexProbe.ps1') `
                -RunRoot $runRoot -Port $Port -PostgresUrl (Resolve-PostgresUrl)
        } catch {
            $probeFailure = $_.Exception.Message
        }
    }
    Save-IndexStatistics (Join-Path $runRoot 'latest-paid-index-after.txt')

    'stop' | Set-Content -LiteralPath $stopPath -Encoding ascii
    if (-not $sampler.WaitForExit(30000)) {
        Stop-Process -Id $sampler.Id -ErrorAction SilentlyContinue
        throw 'The runtime evidence sampler did not stop within thirty seconds.'
    }
    if ($sampler.ExitCode -ne 0 -or
            (Test-Path -LiteralPath (Join-Path $runRoot `
                'evidence-sampler-failure.json'))) {
        throw 'The runtime evidence sampler failed; partial evidence has been preserved.'
    }

    Assert-SourceAndJarUnchanged `
        -ExpectedSource $sourceFacts `
        -JarPath $jarPath `
        -ExpectedJarSha256 ([string]$preflight.jarSha256)

    $resultPath = $null
    if ($DirectConcurrencyCanary) {
        $httpVerdictPath = Join-Path $runRoot 'order-create-http-verdict.json'
        $performanceVerdictPath = Join-Path $runRoot 'performance-verdict.json'
        $httpVerdict = Get-Content -Raw -LiteralPath $httpVerdictPath | ConvertFrom-Json
        $performanceVerdict = Get-Content -Raw -LiteralPath $performanceVerdictPath |
            ConvertFrom-Json
        $resultPath = Join-Path $runRoot 'direct-concurrency-canary-verdict.json'
        [ordered]@{
            verdict = if ($httpVerdict.verdict -eq 'PASS' -and
                    [bool]$performanceVerdict.performancePassed) { 'PASS' } else { 'PASS_WITH_WARNINGS' }
            runId = $RunId
            expectedOrders = $expectedRunOrders
            segment = 'E-P1'
            functionalPassed = $true
            performancePassed = $httpVerdict.verdict -eq 'PASS' -and
                [bool]$performanceVerdict.performancePassed
            http = $httpVerdict
            performance = $performanceVerdict
        } | ConvertTo-Json -Depth 10 |
            Set-Content -LiteralPath $resultPath -Encoding UTF8
    } else {
        $focusedSummary = Join-Path $runRoot `
            'membership-payment-focused-operation-summary.csv'
        & (Join-Path $PSScriptRoot 'New-MembershipSchedulerIndexHikariReport.ps1') `
            -RunRoot $runRoot `
            -ExpectedRowsPerSegment $expectedRowsPerSegment `
            -PreviousRunRoot $PreviousComparableRunRoot `
            -FocusedTimingSummaryCsvPath $focusedSummary `
            -ApplicationLogPath @(
                [string]$application.stdoutPath,
                [string]$application.stderrPath,
                [string]$application.timingLogPath)
        if ($null -ne $probeFailure) {
            throw "The real latest PAID business probe failed: $probeFailure"
        }
        $resultPath = Join-Path $runRoot 'scheduler-index-hikari-verdict.json'
    }

    $suiteRootVerdict = Get-Content -Raw -LiteralPath (
        Join-Path $runRoot 'verdict.json') | ConvertFrom-Json
    $completionStatus = if ([bool]$suiteRootVerdict.performancePassed) {
        'PASS'
    } else { 'PASS_WITH_WARNINGS' }
    Save-RunState -Phase 'COMPLETE' -Status $completionStatus
    Save-Heartbeat -Phase 'COMPLETE'
    if ($KeepApplicationRunningAfterSuite) {
        # 仅在本阶段全部功能与证据门禁完成后交接同一 PID，失败路径始终由 finally 回收。
        $keepApplicationOnExit = $true
    }

    Get-Content -Raw -LiteralPath $resultPath
} catch {
    $orchestratorFailure = $_.Exception.Message
    if ($null -ne $structuredSuiteFailure -and
            $null -ne $structuredSuiteFailure.PSObject.Properties['primaryMessage']) {
        $orchestratorFailure = [string]$structuredSuiteFailure.primaryMessage
    }
    $failureStatus = if (($null -ne $structuredSuiteFailure -and
                $null -ne $structuredSuiteFailure.PSObject.Properties['failureClass'] -and
                [string]$structuredSuiteFailure.failureClass -eq 'TEST_INVALID') -or
            $orchestratorFailure -match
            'TEST_INVALID_|JAR|Source fingerprint changed|configuration drift|sampler|being used by another process|\u65e0\u6cd5\u521b\u5efa\u8be5\u6587\u4ef6') {
        'TEST_INVALID'
    } else { 'FAIL' }
    $failureCode = if ($null -ne $structuredSuiteFailure -and
            $null -ne $structuredSuiteFailure.PSObject.Properties['failureCode']) {
        [string]$structuredSuiteFailure.failureCode
    } elseif ($orchestratorFailure -match '^(?<code>[A-Z][A-Z0-9_]+):') {
        $Matches.code
    } else { 'SCHEDULER_ORCHESTRATION_FAILURE' }
    $originComponent = if ($null -ne $structuredSuiteFailure -and
            $null -ne $structuredSuiteFailure.PSObject.Properties['originComponent']) {
        [string]$structuredSuiteFailure.originComponent
    } else { 'SCHEDULER' }
    $originStage = if ($null -ne $structuredSuiteFailure -and
            $null -ne $structuredSuiteFailure.PSObject.Properties['originStage']) {
        [string]$structuredSuiteFailure.originStage
    } else { 'ORCHESTRATION' }
    [ordered]@{
        schemaVersion = 1
        failureClass = $failureStatus
        failureCode = $failureCode
        primaryMessage = $orchestratorFailure
        originComponent = $originComponent
        originStage = $originStage
        diagnosticStderrPath = $suiteStderrPath
        runId = $RunId
        failedAt = [datetimeoffset]::UtcNow.ToString('O')
        message = $orchestratorFailure
        formalDataCleanupAttempted = $false
        evidencePreserved = $true
    } | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $failurePath -Encoding UTF8
    Save-RunState -Phase 'STOPPED' -Status $failureStatus
    Save-Heartbeat -Phase 'STOPPED'
    throw
} finally {
    if ($null -ne $sampler -and -not $sampler.HasExited) {
        'stop' | Set-Content -LiteralPath $stopPath -Encoding ascii
        if (-not $sampler.WaitForExit(20000)) {
            Stop-Process -Id $sampler.Id -ErrorAction SilentlyContinue
        }
    }
    Stop-OwnedSuiteTree
    if (-not $keepApplicationOnExit) {
        Stop-OwnedApplication
        Archive-OwnedFormalLogs
    }
}
