[CmdletBinding()]
param(
    [string] $RunId = '',
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [string[]] $PreviousScenarioOrdersCsvPath,
    [string] $HostName = '127.0.0.1',
    [int] $Port = 6655,
    [string] $Protocol = 'http',
    [ValidateSet('PERFORMANCE_40K', 'CAPACITY_80K')]
    [string] $RunScale = 'PERFORMANCE_40K',
    [ValidateSet(256)]
    [int] $CreationConcurrency = 256,
    [ValidateSet(256)]
    [int] $HttpConcurrency = 256,
    [ValidateSet(56)]
    [int] $PaymentConcurrency = 56,
    # 兼容旧命令行；同规模预热数量固定由 RunScale 推导，不再接受小样本预热。
    [ValidateSet(0)]
    [int] $WarmupOrderCount = 0,
    [ValidateSet(0)]
    [int] $PrecheckSeconds = 0,
    [ValidateSet(0, 120, 600)]
    [int] $PostgresStabilitySeconds = 120,
    [ValidateRange(60, 600)]
    [int] $InterSegmentSeconds = 120,
    [ValidateSet(384)]
    [int] $PostgresMaxConnections = 384,
    [ValidateSet(256)]
    [int] $HikariMaximumPoolSize = 256,
    [ValidateSet(8)]
    [int] $HikariMinimumIdle = 8,
    [ValidateRange(0, 64)]
    [int] $MaximumNavicatConnections = 0,
    [ValidateSet(64)]
    [int] $RedisWriteBatchSize = 64,
    [ValidateSet(6)]
    [int] $RedisWriteLaneCount = 6,
    [ValidateSet(384)]
    [int] $RedisWriteMaximumInflight = 384,
    # 兼容上一轮命令行；第二轮无论是否传入该开关都始终保留测试后数据。
    [switch] $PreserveDataAfterPass,
    [ValidateSet('E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR')]
    [string] $StartGroupCode = 'E-P1',
    [switch] $SkipInitialGates,
    [switch] $DirectConcurrencyCanary,
    [ValidatePattern('^[A-Za-z0-9_-]{1,128}$')]
    [string] $GoldenBaselineRunId = 'membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048',
    [string] $GoldenBaselineEvidenceRoot = '',
    [string] $ExistingPostgresStabilityGatePath = '',
    [string] $TimingLogRunId = '',
    [switch] $StopAfterWarmupSequence
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'MembershipBoundaryRedis.ps1')
. (Join-Path $PSScriptRoot 'MembershipBoundaryReset.ps1')
Import-Module (Join-Path $PSScriptRoot 'MembershipInterSegmentStability.psm1') -Force
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$expectedJarSha256 = 'b3c924c4abf49266957b9f93076fa2268e5c1e7e447899a1411eff16375ac597'
if ([string]::IsNullOrWhiteSpace($GoldenBaselineEvidenceRoot)) {
    $GoldenBaselineEvidenceRoot = Join-Path $repositoryRoot `
        "loadtest-output\soak\$GoldenBaselineRunId\millisecond-boundary"
}
$baseUrl = "$Protocol`://$HostName`:$Port"
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = 'membership-millisecond-boundary-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
}
if ($RunId -notmatch '^[A-Za-z0-9_-]{1,128}$') {
    throw 'RunId may contain only letters, digits, underscore and hyphen, with at most 128 characters.'
}
if ([string]::IsNullOrWhiteSpace($TimingLogRunId)) { $TimingLogRunId = $RunId }
if ($TimingLogRunId -notmatch '^[A-Za-z0-9_-]{1,128}$') {
    throw 'TimingLogRunId must identify the single reused application log stream.'
}
if ($DirectConcurrencyCanary -and $RunScale -ne 'PERFORMANCE_40K') {
    throw 'Direct concurrency canary is fixed to the 5,000-order E-P1 performance fixture.'
}
if ($StopAfterWarmupSequence -and -not $DirectConcurrencyCanary) {
    throw 'Warmup-only stop mode is restricted to the E-P1 direct-concurrency Canary.'
}
$allGroups = @('E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR')
if ($DirectConcurrencyCanary -and $StartGroupCode -ne 'E-P1') {
    throw 'Direct concurrency canary cannot resume from a later group.'
}
if ($SkipInitialGates -and $StartGroupCode -eq 'E-P1') {
    throw 'Initial gates may only be skipped for a resumed group sequence.'
}
if ($SkipInitialGates -and ($PrecheckSeconds -ne 0 -or $WarmupOrderCount -ne 0)) {
    throw 'Resumed execution must set precheck and warmup counts to zero.'
}
if ($SkipInitialGates -and [string]::IsNullOrWhiteSpace($ExistingPostgresStabilityGatePath)) {
    throw 'A resumed group sequence requires the original PostgreSQL stability gate.'
}
if (-not $SkipInitialGates -and [string]::IsNullOrWhiteSpace($ExistingPostgresStabilityGatePath) -and
        $PostgresStabilitySeconds -notin @(120, 600)) {
    throw 'A formal run requires a 120-second or explicitly extended 600-second PostgreSQL stability gate.'
}
if (-not [string]::IsNullOrWhiteSpace($ExistingPostgresStabilityGatePath) -and
        $PostgresStabilitySeconds -ne 0) {
    throw 'A reused PostgreSQL stability gate must set PostgresStabilitySeconds to zero.'
}
$groups = @(if ($DirectConcurrencyCanary) {
    @('E-P1')
} else {
    $startIndex = [Array]::IndexOf($allGroups, $StartGroupCode)
    @($allGroups[$startIndex..($allGroups.Count - 1)])
})
$outputRoot = Join-Path $repositoryRoot "loadtest-output\soak\$RunId\millisecond-boundary"
$statePath = Join-Path $outputRoot 'soak-state.json'
$suiteVerdictPath = Join-Path $outputRoot 'verdict.json'
$script:currentGroupCode = $null
$script:currentWarmupAttempt = $null
$script:currentOriginStage = 'SUITE_PREFLIGHT'
$tokenRoot = Join-Path $repositoryRoot 'loadtest\local\millisecond-boundary'
$timingOutputDirectory = $outputRoot
$timingLogPath = Join-Path $repositoryRoot 'logs\membership-payment-state-machine.log'
$httpTimingLogPath = Join-Path $repositoryRoot 'logs\membership-order-create-http-events.log'
$forbiddenApplicationPort = [int]('80' + '80')
$expectedSegmentOrders = if ($RunScale -eq 'PERFORMANCE_40K') { 5000 } else { 10000 }
$expectedWarmupOrders = $expectedSegmentOrders
$maximumFormalWallClockSeconds = if ($RunScale -eq 'PERFORMANCE_40K') { 5.556D } else { 11.112D }
$expectedRunOrders = $expectedSegmentOrders * $groups.Count
$expectedUsersPerTier = [int]($expectedSegmentOrders / 4)
$expectedLastUserId = 70000000000000000L + $expectedRunOrders - 1L
$expectedOffsetRepeats = [int]($expectedSegmentOrders / 500)
$expectedTierOffsetMinimum = [int][Math]::Floor($expectedUsersPerTier / 500D)
$expectedTierOffsetMaximum = [int][Math]::Ceiling($expectedUsersPerTier / 500D)
$postgresStabilityBaseline = $null

function Get-TextSha256([string] $Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Copy-EvidenceFileIfNeeded(
        [string] $Path,
        [string] $DestinationDirectory) {
    $sourcePath = (Resolve-Path -LiteralPath $Path).Path
    $destinationPath = [IO.Path]::GetFullPath(
        (Join-Path $DestinationDirectory (Split-Path $sourcePath -Leaf)))
    if ([string]::Equals(
            $sourcePath, $destinationPath,
            [StringComparison]::OrdinalIgnoreCase)) {
        return
    }
    Copy-Item -LiteralPath $sourcePath -Destination $destinationPath -Force
}

function Get-SourceFingerprint {
    Push-Location $repositoryRoot
    try {
        $paths = @(
            'ai-temperate-common', 'ai-temperate-model', 'ai-temperate-mapper',
            'ai-temperate-service', 'ai-temperate-web', 'loadtest', 'sql', 'docs', 'pom.xml')
        # 源码指纹只消费 Git 的标准输出；工作区换行提示若持续写入监督终端，会淹没区段心跳并使长会话失稳。
        $head = (& git rev-parse HEAD 2>$null).Trim()
        $diff = (& git diff --binary HEAD -- @paths 2>$null | Out-String)
        $untracked = @(& git ls-files --others --exclude-standard -- @paths 2>$null |
            Where-Object { $_ } | Sort-Object)
        $facts = foreach ($relative in $untracked) {
            $absolute = Join-Path $repositoryRoot $relative
            if (Test-Path -LiteralPath $absolute -PathType Leaf) {
                "$relative=$((Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash)"
            }
        }
        return Get-TextSha256 (($head, (Get-TextSha256 $diff)) + $facts -join "`n")
    } finally {
        Pop-Location
    }
}

function Resolve-PostgresUrl {
    if ($env:MEMBERSHIP_PAYMENT_POSTGRES_URL) { return $env:MEMBERSHIP_PAYMENT_POSTGRES_URL }
    if ($env:POSTGRES_URL) { return $env:POSTGRES_URL }
    return 'postgresql://postgres@127.0.0.1:5431/ai_temperate'
}

function Get-PostgresProcessIdentity {
    $listenerPids = @(Get-NetTCPConnection -State Listen -LocalPort 5431 `
        -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique |
        Where-Object { $_ -gt 0 })
    if ($listenerPids.Count -ne 1) {
        throw "TEST_INVALID_POSTGRES: port 5431 does not have exactly one postmaster listener PID."
    }
    $started = @(& psql -w (Resolve-PostgresUrl) -X -q -A -t -v ON_ERROR_STOP=1 `
        -c "SELECT pg_postmaster_start_time();")
    if ($LASTEXITCODE -ne 0 -or $started.Count -ne 1 -or
            [string]::IsNullOrWhiteSpace($started[0])) {
        throw 'TEST_INVALID_POSTGRES: pg_postmaster_start_time() could not be sampled.'
    }
    $postmaster = Get-CimInstance Win32_Process -Filter "ProcessId=$($listenerPids[0])" `
        -ErrorAction Stop
    if ($null -eq $postmaster -or $postmaster.Name -ne 'postgres.exe' -or
            $postmaster.CommandLine -notmatch '(?i)postgresql[/\\]data') {
        throw 'TEST_INVALID_POSTGRES: the 5431 listener is not the fixed Windows PostgreSQL process.'
    }
    $parent = Get-CimInstance Win32_Process -Filter (
        "ProcessId=$($postmaster.ParentProcessId)") -ErrorAction SilentlyContinue
    $service = Get-Service -Name 'postgresql-x64-18-5431' -ErrorAction SilentlyContinue
    return [pscustomobject]@{
        listenerPid = [int]$listenerPids[0]
        postmasterStartedAt = $started[0].Trim()
        windowsProcessHost = [ordered]@{
            postmasterCommandLine = [string]$postmaster.CommandLine
            parentPid = [int]$postmaster.ParentProcessId
            parentName = if ($null -eq $parent) { $null } else { [string]$parent.Name }
            parentCommandLine = if ($null -eq $parent) { $null } else { [string]$parent.CommandLine }
            serviceName = if ($null -eq $service) { $null } else { [string]$service.Name }
            serviceStatus = if ($null -eq $service) { $null } else { [string]$service.Status }
            serviceStartType = if ($null -eq $service) { $null } else { [string]$service.StartType }
        }
    }
}

function Get-PostgresSettings {
    $settings = @(& psql -w (Resolve-PostgresUrl) -X -q -A -t -v ON_ERROR_STOP=1 `
        -c "SHOW data_directory; SHOW log_directory; SHOW max_connections;")
    if ($LASTEXITCODE -ne 0 -or $settings.Count -ne 3) {
        throw 'TEST_INVALID_POSTGRES: PostgreSQL data/log directory or max_connections could not be resolved.'
    }
    $dataDirectory = [IO.Path]::GetFullPath($settings[0].Trim()).TrimEnd('\','/')
    $expectedDataDirectory = [IO.Path]::GetFullPath(
        'C:\Users\damn\Desktop\postgresql\data').TrimEnd('\','/')
    if (-not [string]::Equals(
            $dataDirectory, $expectedDataDirectory, [StringComparison]::OrdinalIgnoreCase)) {
        throw "TEST_INVALID_POSTGRES: data_directory is outside the fixed 5431 boundary: $dataDirectory"
    }
    $maximumConnections = [int]$settings[2].Trim()
    if ($maximumConnections -ne 384 -or $maximumConnections -ne $PostgresMaxConnections) {
        throw "TEST_INVALID_POSTGRES: max_connections must remain 384, actual=$maximumConnections"
    }
    $logDirectory = $settings[1].Trim()
    if (-not [IO.Path]::IsPathRooted($logDirectory)) {
        $logDirectory = Join-Path $dataDirectory $logDirectory
    }
    return [pscustomobject]@{
        dataDirectory = $dataDirectory
        logDirectory = [IO.Path]::GetFullPath($logDirectory)
        maxConnections = $maximumConnections
    }
}

function Get-PostgresLogPaths([string] $LogDirectory) {
    $paths = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::OrdinalIgnoreCase)
    if (Test-Path -LiteralPath $LogDirectory -PathType Container) {
        foreach ($log in @(Get-ChildItem -LiteralPath $LogDirectory -File -ErrorAction Stop)) {
            [void]$paths.Add($log.FullName)
        }
    }
    # Windows 手工启动通常由 cmd.exe 把 stderr 重定向到数据目录外；该固定日志也必须进入增量崩溃扫描。
    $externalLogPath = 'C:\Users\damn\Desktop\postgresql\postgresql-5431.log'
    if (Test-Path -LiteralPath $externalLogPath -PathType Leaf) {
        [void]$paths.Add([IO.Path]::GetFullPath($externalLogPath))
    }
    return @($paths | Sort-Object)
}

function Get-PostgresLogOffsets([string] $LogDirectory) {
    $offsets = @{}
    foreach ($logPath in @(Get-PostgresLogPaths -LogDirectory $LogDirectory)) {
        $offsets[$logPath] = [long](Get-Item -LiteralPath $logPath -ErrorAction Stop).Length
    }
    return $offsets
}

function Get-PostgresCrashSignatures([hashtable] $Offsets, [string] $LogDirectory) {
    $matches = [Collections.Generic.List[string]]::new()
    foreach ($logPath in @(Get-PostgresLogPaths -LogDirectory $LogDirectory)) {
        $log = Get-Item -LiteralPath $logPath -ErrorAction Stop
        $offset = if ($Offsets.ContainsKey($logPath) -and
                [long]$Offsets[$logPath] -le [long]$log.Length) {
            [long]$Offsets[$logPath]
        } else {
            0L
        }
        $stream = [IO.FileStream]::new(
            $logPath,
            [IO.FileMode]::Open,
            [IO.FileAccess]::Read,
            [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
        try {
            [void]$stream.Seek($offset, [IO.SeekOrigin]::Begin)
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true, 4096, $true)
            try {
                while ($null -ne ($line = $reader.ReadLine())) {
                    if ($line -match '0xC0000142|error code 487|database system was interrupted|database system was not properly shut down|automatic recovery in progress|redo starts at|PANIC:|server process .* was terminated|terminating any other active server processes|all server processes terminated; reinitializing|abnormal database system shutdown') {
                        $matches.Add("$($log.Name):$line")
                    }
                }
            } finally {
                $reader.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
    }
    return @($matches)
}

function Assert-PostgresIdentityUnchanged {
    if ($null -eq $script:postgresStabilityBaseline) {
        throw 'TEST_INVALID_POSTGRES: PostgreSQL stability baseline is absent.'
    }
    $current = Get-PostgresProcessIdentity
    if ($current.listenerPid -ne $script:postgresStabilityBaseline.listenerPid -or
            $current.postmasterStartedAt -ne
                $script:postgresStabilityBaseline.postmasterStartedAt) {
        throw 'TEST_INVALID_POSTGRES: PostgreSQL postmaster PID or start time changed during the run.'
    }
    $signatures = @(Get-PostgresCrashSignatures `
        -Offsets $script:postgresStabilityBaseline.logOffsets `
        -LogDirectory $script:postgresStabilityBaseline.logDirectory)
    if ($signatures.Count -ne 0) {
        throw "TEST_INVALID_POSTGRES: PostgreSQL crash signature appeared: $($signatures -join '; ')"
    }
}

function Assert-PostgresProcessStability([int] $Seconds) {
    $observedFrom = [datetimeoffset]::UtcNow
    $identity = Get-PostgresProcessIdentity
    $settings = Get-PostgresSettings
    $logDirectory = $settings.logDirectory
    $logOffsets = Get-PostgresLogOffsets -LogDirectory $logDirectory
    $watchSqlPath = Join-Path $outputRoot 'postgres-stability-watch.sql'
    $watchOutputPath = Join-Path $outputRoot 'postgres-stability-watch.csv'
    $watchErrorPath = Join-Path $outputRoot 'postgres-stability-watch.stderr.log'
    @'
SELECT clock_timestamp(), pg_postmaster_start_time(), pg_backend_pid();
\watch 1
'@ | Set-Content -LiteralPath $watchSqlPath -Encoding utf8
    $watch = $null
    try {
        $watch = Start-Process -FilePath 'psql' -ArgumentList @(
            '-w', (Resolve-PostgresUrl), '-X', '-q', '-A', '-t', '-F', '|',
            '-v', 'ON_ERROR_STOP=1', '-f', ('"' + $watchSqlPath + '"')) `
            -RedirectStandardOutput $watchOutputPath `
            -RedirectStandardError $watchErrorPath `
            -WindowStyle Hidden -PassThru
        $deadline = [datetimeoffset]::UtcNow.AddSeconds($Seconds)
        while ([datetimeoffset]::UtcNow -lt $deadline) {
            Start-Sleep -Seconds 1
            $watch.Refresh()
            if ($watch.HasExited) {
                throw "long-lived psql watcher exited with code $($watch.ExitCode)."
            }
            $listenerPids = @(Get-NetTCPConnection -State Listen -LocalPort 5431 `
                -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique |
                Where-Object { $_ -gt 0 })
            if ($listenerPids.Count -ne 1 -or [int]$listenerPids[0] -ne $identity.listenerPid) {
                throw 'PostgreSQL listener PID changed during the stability window.'
            }
            $remaining = [Math]::Max(
                0,
                [Math]::Ceiling(($deadline - [datetimeoffset]::UtcNow).TotalSeconds))
            if ($remaining % 10 -eq 0) {
                Write-Output "POSTGRES_STABILITY_HEARTBEAT remainingSeconds=$remaining pid=$($identity.listenerPid)"
            }
        }
        $signatures = @(Get-PostgresCrashSignatures `
            -Offsets $logOffsets -LogDirectory $logDirectory)
        if ($signatures.Count -ne 0) {
            throw "PostgreSQL crash signature appeared: $($signatures -join '; ')"
        }
        $watch.Refresh()
        $watchErrors = if (Test-Path -LiteralPath $watchErrorPath -PathType Leaf) {
            $rawWatchErrors = Get-Content -Raw -LiteralPath $watchErrorPath
            if ($null -eq $rawWatchErrors) { '' } else { $rawWatchErrors.Trim() }
        } else { '' }
        if (-not [string]::IsNullOrWhiteSpace($watchErrors)) {
            throw "long-lived psql watcher wrote stderr: $watchErrors"
        }
        $watchRows = @(Get-Content -LiteralPath $watchOutputPath | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        })
        if ($watchRows.Count -lt $Seconds) {
            throw "long-lived psql watcher lost samples: expectedAtLeast=$Seconds actual=$($watchRows.Count)"
        }
        $watchBackendPids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($watchRow in $watchRows) {
            $watchFields = @($watchRow.Split('|'))
            if ($watchFields.Count -ne 3 -or
                    $watchFields[1].Trim() -ne $identity.postmasterStartedAt -or
                    -not $watchBackendPids.Add($watchFields[2].Trim()) -and
                        $watchBackendPids.Count -gt 1) {
                throw "long-lived psql watcher emitted an invalid or reconnected sample: $watchRow"
            }
        }
        if ($watchBackendPids.Count -ne 1) {
            throw 'long-lived psql watcher changed backend PID during the stability gate.'
        }
        $script:postgresStabilityBaseline = [pscustomobject]@{
            listenerPid = $identity.listenerPid
            postmasterStartedAt = $identity.postmasterStartedAt
            windowsProcessHost = $identity.windowsProcessHost
            observedFrom = $observedFrom
            dataDirectory = $settings.dataDirectory
            maxConnections = $settings.maxConnections
            logDirectory = $logDirectory
            logOffsets = $logOffsets
            monitoredLogPaths = @($logOffsets.Keys | Sort-Object)
        }
        [ordered]@{
            verdict = 'PASS'
            observedSeconds = $Seconds
            listenerPid = $identity.listenerPid
            postmasterStartedAt = $identity.postmasterStartedAt
            windowsProcessHost = $identity.windowsProcessHost
            dataDirectory = $settings.dataDirectory
            maxConnections = $settings.maxConnections
            logDirectory = $logDirectory
            logOffsets = $logOffsets
            monitoredLogPaths = @($logOffsets.Keys | Sort-Object)
            observedFrom = $observedFrom.ToString('O')
            observedUntil = [datetimeoffset]::UtcNow.ToString('O')
            longLivedPsqlOutput = $watchOutputPath
            longLivedPsqlSampleCount = $watchRows.Count
            longLivedPsqlBackendPid = @($watchBackendPids)[0]
            samplerStderrEmpty = $true
            crashSignatureCount = 0
        } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (
            Join-Path $outputRoot 'postgres-stability-gate.json') -Encoding utf8
    } catch {
        [ordered]@{
            verdict = 'TEST_INVALID'
            observedSeconds = $Seconds
            listenerPid = $identity.listenerPid
            postmasterStartedAt = $identity.postmasterStartedAt
            observedFrom = $observedFrom.ToString('O')
            failedAt = [datetimeoffset]::UtcNow.ToString('O')
            message = $_.Exception.Message
        } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (
            Join-Path $outputRoot 'postgres-stability-gate.json') -Encoding utf8
        throw "TEST_INVALID_POSTGRES: $($_.Exception.Message)"
    } finally {
        if ($null -ne $watch) {
            $watch.Refresh()
            if (-not $watch.HasExited) {
                Stop-Process -Id $watch.Id -Force -ErrorAction SilentlyContinue
                [void]$watch.WaitForExit(5000)
            }
            $watch.Dispose()
        }
    }
}

function Import-PostgresStabilityBaseline([string] $Path) {
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    $gate = Get-Content -Raw -LiteralPath $resolvedPath | ConvertFrom-Json
    $expectedDataDirectory = [IO.Path]::GetFullPath(
        'C:\Users\damn\Desktop\postgresql\data').TrimEnd('\','/')
    $gateDataDirectory = [IO.Path]::GetFullPath(
        [string]$gate.dataDirectory).TrimEnd('\','/')
    if ([string]$gate.verdict -ne 'PASS' -or [int]$gate.observedSeconds -lt 120 -or
            [int]$gate.maxConnections -ne 384 -or
            -not [string]::Equals(
                $gateDataDirectory, $expectedDataDirectory,
                [StringComparison]::OrdinalIgnoreCase)) {
        throw 'TEST_INVALID_POSTGRES: reused PostgreSQL gate is not a valid fixed 5431/384 baseline.'
    }
    $offsets = @{}
    foreach ($property in $gate.logOffsets.PSObject.Properties) {
        $offsets[$property.Name] = [long]$property.Value
    }
    $script:postgresStabilityBaseline = [pscustomobject]@{
        listenerPid = [int]$gate.listenerPid
        postmasterStartedAt = [string]$gate.postmasterStartedAt
        windowsProcessHost = $gate.windowsProcessHost
        observedFrom = [datetimeoffset]$gate.observedFrom
        dataDirectory = $gateDataDirectory
        maxConnections = [int]$gate.maxConnections
        logDirectory = [IO.Path]::GetFullPath([string]$gate.logDirectory)
        logOffsets = $offsets
    }
    Assert-PostgresIdentityUnchanged
    $currentSettings = Get-PostgresSettings
    [ordered]@{
        verdict = 'PASS'
        observedSeconds = 0
        reusedValidatedGate = $resolvedPath
        originalObservedSeconds = [int]$gate.observedSeconds
        listenerPid = $script:postgresStabilityBaseline.listenerPid
        postmasterStartedAt = $script:postgresStabilityBaseline.postmasterStartedAt
        windowsProcessHost = $script:postgresStabilityBaseline.windowsProcessHost
        dataDirectory = $currentSettings.dataDirectory
        maxConnections = $currentSettings.maxConnections
        logDirectory = $script:postgresStabilityBaseline.logDirectory
        logOffsets = $offsets
        monitoredLogPaths = @($offsets.Keys | Sort-Object)
        validatedAt = [datetimeoffset]::UtcNow.ToString('O')
        crashSignatureCount = 0
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (
        Join-Path $outputRoot 'postgres-stability-gate.json') -Encoding utf8
}

function Assert-PortBoundary {
    $allowed = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object {
            $_.LocalPort -eq $Port -and
            $_.LocalAddress -in @('127.0.0.1', '0.0.0.0', '::', '::1')
        })
    if ($allowed.Count -ne 1) {
        throw 'Boundary suite requires exactly one application listener on port 6655.'
    }
    $forbidden = @(Get-NetTCPConnection -State Listen `
        -LocalPort $forbiddenApplicationPort -ErrorAction SilentlyContinue)
    if ($forbidden.Count -ne 0) {
        throw 'A forbidden second application listener is active.'
    }
}

function Assert-PostgresBoundaryBaseline {
    $sql = @'
SELECT
    (SELECT COUNT(*) FROM userloginidentity
      WHERE id BETWEEN 70000000000000000 AND 70000000000079999),
    (SELECT COUNT(*) FROM user_profile
      WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000079999),
    (SELECT COUNT(*) FROM user_profile
      WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000079999
        AND account_status = 0),
    COUNT(*),
    COUNT(*) FILTER (
      WHERE membership_tier = 0
        AND quota_balance_minor = 5000
        AND quota_period_started_at IS NULL
        AND quota_period_ends_at IS NOT NULL
        AND membership_expires_at IS NULL),
    (SELECT COUNT(*) FROM membership_order
      WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000079999),
    (SELECT COUNT(*)
       FROM membership_payment_callback callback
       JOIN membership_order payment_order ON payment_order.id = callback.order_id
      WHERE payment_order.login_identity_id
            BETWEEN 70000000000000000 AND 70000000000079999)
FROM user_membership_quota
WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000079999;
'@
    $raw = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 -A -t -F '|' -c $sql)
    if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1) {
        throw 'PostgreSQL FREE baseline inspection failed.'
    }
    $actual = @($raw[0].Trim().Split('|') | ForEach-Object { [long]$_ })
    $expected = @(80000L, 80000L, 80000L, 80000L, 80000L, 0L, 0L)
    if (($actual -join '|') -ne ($expected -join '|')) {
        throw "PostgreSQL FREE baseline is invalid: $($actual -join '|')"
    }
}

function Assert-RedisBoundaryBaseline {
    $container = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
    foreach ($pattern in @(
            'ait:*:payment:membership-order:v[12]:snapshot:*',
            'ait:*:payment:membership-order:v[12]:callback:*',
            'ait:*:payment:provider-result:v[12]:status:*',
            'ait:*:payment:callback:v[12]:data:*')) {
        $keys = @(Invoke-MembershipBoundaryRedisCli -Container $container `
            -Arguments @('--scan', '--pattern', $pattern))
        if ($keys.Count -ne 0) {
            throw "Redis boundary state facts are not empty: $pattern count=$($keys.Count)"
        }
    }
    $queues = Invoke-RestMethod -Method Get `
        -Uri "$baseUrl/internal/test/membership-payments/loadtest-inspection/queues" `
        -TimeoutSec 15
    foreach ($property in @(
        'callbackReadySize', 'callbackProcessingSize', 'dirtySize', 'dirtyProcessingSize')) {
        if ([long]$queues.$property -ne 0L) {
            throw "Redis membership queue is not empty: $property=$($queues.$property)"
        }
    }
}

function Assert-RabbitBoundaryBaseline {
    $connectionArguments = @(
        'list_connections', '--formatter', 'json', 'name', 'channel_max')
    $connectionRaw = @(& docker exec rabbitmq1 rabbitmqctl @connectionArguments 2>$null)
    if ($LASTEXITCODE -ne 0 -or $connectionRaw.Count -eq 0) {
        throw 'RabbitMQ connection capacity inspection failed.'
    }
    $parsedConnections = ($connectionRaw -join "`n") | ConvertFrom-Json
    $connections = @($parsedConnections)
    if ($connections.Count -eq 0 -or @($connections | Where-Object {
            [int]$_.channel_max -lt 512
        }).Count -ne 0) {
        throw 'RabbitMQ negotiated channel_max is below the fixed 512-channel boundary.'
    }

    $arguments = @('list_queues', '--formatter', 'json', 'name', 'consumers',
        'messages_ready', 'messages_unacknowledged', 'durable', 'type')
    $raw = @(& docker exec rabbitmq1 rabbitmqctl @arguments 2>$null)
    if ($LASTEXITCODE -ne 0 -or $raw.Count -eq 0) {
        throw 'RabbitMQ boundary baseline inspection failed.'
    }
    $queues = @(($raw -join "`n") | ConvertFrom-Json)
    $membershipQueues = @($queues | Where-Object { $_.name -like 'membership.*' })
    if ($membershipQueues.Count -eq 0 -or @($membershipQueues | Where-Object {
            [long]$_.messages_ready -ne 0L -or [long]$_.messages_unacknowledged -ne 0L
        }).Count -ne 0) {
        throw 'RabbitMQ membership Ready, Unacked or DLQ baseline is not empty.'
    }
    foreach ($name in @('membership.payment.check.queue', 'membership.closing.check.queue')) {
        $row = @($queues | Where-Object name -eq $name)
        if ($row.Count -ne 1 -or [int]$row[0].consumers -ne 48) {
            throw "RabbitMQ membership queue must have exactly forty-eight consumers: $name"
        }
    }

    $consumerArguments = @(
        'list_consumers', '--formatter', 'json', 'queue_name', 'prefetch_count', 'active')
    $consumerRaw = @(& docker exec rabbitmq1 rabbitmqctl @consumerArguments 2>$null)
    if ($LASTEXITCODE -ne 0 -or $consumerRaw.Count -eq 0) {
        throw 'RabbitMQ membership consumer inspection failed.'
    }
    $consumers = @(($consumerRaw -join "`n") | ConvertFrom-Json)
    foreach ($name in @('membership.payment.check.queue', 'membership.closing.check.queue')) {
        $rows = @($consumers | Where-Object queue_name -eq $name)
        if ($rows.Count -ne 48 -or @($rows | Where-Object {
                [int]$_.prefetch_count -ne 20 -or -not [bool]$_.active
            }).Count -ne 0) {
            throw "RabbitMQ membership consumers must be 48 active listeners with prefetch 20: $name"
        }
    }
}

function Invoke-WarmupHttpRequest(
    [string] $Method,
    [string] $Path,
    [hashtable] $Headers,
    [int[]] $ExpectedStatus,
    [AllowNull()] [string] $Body = $null) {
    $arguments = @{
        Method = $Method
        Uri = "$baseUrl$Path"
        Headers = $Headers
        TimeoutSec = 30
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = $Body
    }
    $response = Invoke-WebRequest @arguments
    if ([int]$response.StatusCode -notin $ExpectedStatus) {
        $preview = ([string]$response.Content)
        if ($preview.Length -gt 256) { $preview = $preview.Substring(0, 256) }
        throw "Membership warmup $Method $Path returned HTTP $($response.StatusCode): $preview"
    }
    return $response
}

function Wait-WarmupOrdersTerminal(
    [long] $FirstUserId,
    [int] $OrderCount,
    [int] $TimeoutSeconds = 120) {
    $lastUserId = $FirstUserId + $OrderCount - 1L
    $deadline = [datetimeoffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $sql = @"
SELECT COUNT(*),
       COUNT(*) FILTER (WHERE status NOT IN (2, 3, 4)),
       COUNT(*) FILTER (
         WHERE entitlement_resolution IS NULL
            OR entitlement_resolved_at IS NULL)
FROM membership_order
WHERE login_identity_id BETWEEN $FirstUserId AND $lastUserId;
"@
        $raw = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 -A -t -F '|' -c $sql)
        if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1) {
            throw 'Membership warmup PostgreSQL settlement inspection failed.'
        }
        $parts = @($raw[0].Trim().Split('|'))
        if ($parts.Count -ne 3) {
            throw 'Membership warmup PostgreSQL settlement result is invalid.'
        }
        if ([int]$parts[0] -eq $OrderCount -and
                [int]$parts[1] -eq 0 -and [int]$parts[2] -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([datetimeoffset]::UtcNow -lt $deadline)
    throw "Membership warmup orders did not become terminal and entitlement-resolved within $TimeoutSeconds seconds."
}

function Remove-FormalWarmupData([Collections.Generic.List[object]] $Records) {
    if ($Records.Count -eq 0) { return }

    foreach ($record in $Records) {
        if (-not [bool]$record.cancelled) {
            $cancel = Invoke-WarmupHttpRequest `
                -Method 'POST' `
                -Path "/api/user/membership-orders/$($record.orderId)/cancel" `
                -Headers $record.headers `
                -ExpectedStatus @(200)
            $cancelBody = $cancel.Content | ConvertFrom-Json
            if ([string]$cancelBody.status -ne 'CANCELLED') {
                throw "Membership warmup order did not cancel: $($record.orderId)"
            }
            $record.cancelled = $true
        }
    }

    Wait-WarmupOrdersTerminal `
        -FirstUserId ([long]$Records[0].userId) `
        -OrderCount $Records.Count

    # 支付发起已发布十秒 PENDING 检查；等它消费终态后再清理 Redis，避免预热消息进入正式计时窗口。
    $lastPaymentAt = $Records | ForEach-Object { [datetimeoffset]$_.paymentCompletedAt } |
        Sort-Object | Select-Object -Last 1
    $rabbitSafeAt = $lastPaymentAt.AddSeconds(12)
    $remainingMillis = [Math]::Ceiling(($rabbitSafeAt - [datetimeoffset]::UtcNow).TotalMilliseconds)
    if ($remainingMillis -gt 0) {
        Start-Sleep -Milliseconds ([int]$remainingMillis)
    }
    Assert-RabbitBoundaryBaseline

    $firstUserId = [long]$Records[0].userId
    $lastUserId = $firstUserId + $Records.Count - 1L
    $callbackSql = @"
SELECT RTRIM(TRANSLATE(ENCODE(callback.id, 'base64'), '+/', '-_'), '=')
FROM membership_payment_callback callback
JOIN membership_order payment_order ON payment_order.id = callback.order_id
WHERE payment_order.login_identity_id BETWEEN $firstUserId AND $lastUserId
ORDER BY 1;
"@
    $callbackIds = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 -A -t -c $callbackSql |
        ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0) {
        throw 'Membership warmup callback enumeration failed.'
    }
    $redisContainer = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
    $redisReset = Remove-MembershipBoundaryRedisOrderArtifacts `
        -Container $redisContainer `
        -OrderIds @($Records | ForEach-Object { [string]$_.orderId }) `
        -CallbackIds @($callbackIds)
    $redisReset | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath (Join-Path $outputRoot 'warmup-redis-reset.json') -Encoding UTF8

    $resetBody = [ordered]@{
        orderIds = @($Records | ForEach-Object { [string]$_.orderId })
    } | ConvertTo-Json -Depth 3 -Compress
    $reset = Invoke-RestMethod -Method Post `
        -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/reset" `
        -ContentType 'application/json' `
        -Body $resetBody `
        -TimeoutSec 120
    if (-not $reset.prepared -or $reset.identityCount -ne 80000 -or
            $reset.profileCount -ne 80000 -or $reset.quotaCount -ne 80000 -or
            $reset.orderCount -ne 0 -or $reset.callbackCount -ne 0) {
        throw 'Membership warmup exact reset did not restore the clean FREE baseline.'
    }
    Assert-PostgresBoundaryBaseline
    Assert-RedisBoundaryBaseline
    Assert-RabbitBoundaryBaseline
}

function Invoke-FormalBusinessWarmup([int] $OrderCount) {
    $tokenPageResponse = Invoke-RestMethod -Method Post `
        -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/tokens/0" `
        -TimeoutSec 60
    # PowerShell 7 会把顶层 JSON 数组作为单个结果对象；赋值后再展开才能稳定得到五百条 Token。
    $tokenPage = @($tokenPageResponse)
    if ($tokenPage.Count -ne 500 -or $OrderCount -gt $tokenPage.Count) {
        throw 'Membership warmup could not obtain the fixed first token page.'
    }

    $records = [Collections.Generic.List[object]]::new()
    $failure = $null
    try {
        $warmupIndex = 0
        foreach ($token in @($tokenPage | Select-Object -First $OrderCount)) {
            $expectedUserId = 70000000000000000L + $warmupIndex
            if ([long]$token.userId -ne $expectedUserId -or
                    [string]::IsNullOrWhiteSpace([string]$token.accessToken)) {
                throw 'Membership warmup token page is empty or not in fixed user order.'
            }
            $headers = @{
                Authorization = 'Bearer ' + [string]$token.accessToken
                Accept = 'application/json'
            }
            $createBody = [ordered]@{
                targetTier = 'PLUS'
                payType = 'alipay'
                idempotencyKey = [guid]::NewGuid().ToString()
            } | ConvertTo-Json -Compress
            $createStartedAt = [datetimeoffset]::UtcNow
            $create = Invoke-WarmupHttpRequest `
                -Method 'POST' `
                -Path '/api/user/membership-orders' `
                -Headers $headers `
                -ExpectedStatus @(201) `
                -Body $createBody
            $createResult = $create.Content | ConvertFrom-Json
            $orderId = [string]$createResult.orderId
            if ($orderId -notmatch '^[A-Za-z0-9_-]{22}$') {
                throw 'Membership warmup create response contains an invalid order ID.'
            }
            $record = [pscustomobject]@{
                userId = [long]$token.userId
                orderId = $orderId
                headers = $headers
                createStartedAt = $createStartedAt
                createCompletedAt = [datetimeoffset]::UtcNow
                paymentCompletedAt = [datetimeoffset]::UtcNow
                cancelled = $false
            }
            $records.Add($record)

            [void](Invoke-WarmupHttpRequest `
                -Method 'POST' `
                -Path "/api/user/membership-orders/$orderId/payment-attempts" `
                -Headers $headers `
                -ExpectedStatus @(200, 201))
            $record.paymentCompletedAt = [datetimeoffset]::UtcNow

            $cancel = Invoke-WarmupHttpRequest `
                -Method 'POST' `
                -Path "/api/user/membership-orders/$orderId/cancel" `
                -Headers $headers `
                -ExpectedStatus @(200)
            $cancelResult = $cancel.Content | ConvertFrom-Json
            if ([string]$cancelResult.status -ne 'CANCELLED') {
                throw "Membership warmup order did not enter CANCELLED: $orderId"
            }
            $record.cancelled = $true
            $warmupIndex += 1
        }
    } catch {
        $failure = $_
    } finally {
        Remove-FormalWarmupData -Records $records
        [ordered]@{
            warmupOrderCount = $records.Count
            requestedOrderCount = $OrderCount
            cleaned = $true
            orders = @($records | ForEach-Object {
                [ordered]@{
                    userId = $_.userId
                    orderId = $_.orderId
                    createStartedAt = $_.createStartedAt.ToString('O')
                    paymentCompletedAt = $_.paymentCompletedAt.ToString('O')
                }
            })
            completedAt = [datetimeoffset]::UtcNow.ToString('O')
        } | ConvertTo-Json -Depth 6 |
            Set-Content -LiteralPath (Join-Path $outputRoot 'formal-warmup.json') -Encoding UTF8
    }
    if ($null -ne $failure) { throw $failure }
}

function Save-RedisPerformanceSnapshot([string] $Name) {
    $container = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
    $slowlogLengthRaw = @(Invoke-MembershipBoundaryRedisCli -Container $container `
        -Arguments @('SLOWLOG', 'LEN'))
    if ($slowlogLengthRaw.Count -ne 1) {
        throw 'Redis SLOWLOG length inspection returned an unexpected result.'
    }
    $snapshot = [ordered]@{
        capturedAt = [datetimeoffset]::UtcNow.ToString('O')
        slowlogLength = [long]$slowlogLengthRaw[0]
        slowlog = @(
            Invoke-MembershipBoundaryRedisCli -Container $container `
                -Arguments @('SLOWLOG', 'GET', '256'))
        commandstats = @(
            Invoke-MembershipBoundaryRedisCli -Container $container `
                -Arguments @('INFO', 'commandstats')) -join "`n"
        latencystats = @(
            Invoke-MembershipBoundaryRedisCli -Container $container `
                -Arguments @('INFO', 'latencystats')) -join "`n"
        latencyLatest = @(
            Invoke-MembershipBoundaryRedisCli -Container $container `
                -Arguments @('LATENCY', 'LATEST'))
        cpu = @(
            Invoke-MembershipBoundaryRedisCli -Container $container `
                -Arguments @('INFO', 'cpu')) -join "`n"
        memory = @(
            Invoke-MembershipBoundaryRedisCli -Container $container `
                -Arguments @('INFO', 'memory')) -join "`n"
    }
    $snapshot | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath (Join-Path $outputRoot "redis-performance-$Name.json") `
            -Encoding UTF8
}

function Get-MembershipRedisWriteRejectedCount {
    try {
        $metric = Invoke-RestMethod -Method Get -Uri (
            "$baseUrl/actuator/metrics/membership.payment.redis.write.rejected.total") `
            -TimeoutSec 15
    } catch {
        $statusCode = if ($null -ne $_.Exception.Response) {
            [int]$_.Exception.Response.StatusCode
        } else { 0 }
        if ($statusCode -eq 404) { return 0D }
        throw
    }
    return [double](@($metric.measurements | Measure-Object -Property value -Sum).Sum)
}

function Save-ApplicationPerformanceSnapshot([string] $Name) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port `
        -ErrorAction Stop |
        Where-Object {
            # Windows 可能同时存在其他网卡上的同端口监听；这里只采集受控回环应用 JVM。
            $_.LocalAddress -in @('127.0.0.1', '0.0.0.0', '::', '::1')
        } |
        Select-Object -ExpandProperty OwningProcess -Unique)
    if ($listeners.Count -ne 1) {
        throw 'Cannot identify exactly one application process for the JVM baseline.'
    }
    $process = Get-Process -Id $listeners[0] -ErrorAction Stop
    $heapInfo = @(& jcmd $process.Id GC.heap_info 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'JVM GC heap baseline capture failed.'
    }
    $vmVersion = @(& jcmd $process.Id VM.version 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'JVM version baseline capture failed.'
    }
    [ordered]@{
        capturedAt = [datetimeoffset]::UtcNow.ToString('O')
        pid = $process.Id
        cpuSeconds = [Math]::Round($process.CPU, 3)
        workingSetBytes = $process.WorkingSet64
        privateMemoryBytes = $process.PrivateMemorySize64
        threadCount = $process.Threads.Count
        handleCount = $process.HandleCount
        redisWriteRejectedCount = Get-MembershipRedisWriteRejectedCount
        gcHeapInfo = @($heapInfo)
        vmVersion = @($vmVersion)
    } | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $outputRoot "application-performance-$Name.json") `
            -Encoding UTF8
}

function Get-RabbitInterSegmentObservation {
    $connectionArguments = @(
        'list_connections', '--formatter', 'json', 'name', 'channel_max')
    $connectionRaw = @(& docker exec rabbitmq1 rabbitmqctl @connectionArguments 2>$null)
    if ($LASTEXITCODE -ne 0 -or $connectionRaw.Count -eq 0) {
        throw 'RABBIT_INSPECTION_FAILED: RabbitMQ connection capacity inspection failed.'
    }
    $connections = @((($connectionRaw -join "`n") | ConvertFrom-Json))

    $queueArguments = @('list_queues', '--formatter', 'json', 'name', 'consumers',
        'messages_ready', 'messages_unacknowledged', 'durable', 'type')
    $queueRaw = @(& docker exec rabbitmq1 rabbitmqctl @queueArguments 2>$null)
    if ($LASTEXITCODE -ne 0 -or $queueRaw.Count -eq 0) {
        throw 'RABBIT_INSPECTION_FAILED: RabbitMQ queue inspection failed.'
    }
    $rabbitQueues = @((($queueRaw -join "`n") | ConvertFrom-Json))

    $consumerArguments = @(
        'list_consumers', '--formatter', 'json', 'queue_name', 'prefetch_count', 'active')
    $consumerRaw = @(& docker exec rabbitmq1 rabbitmqctl @consumerArguments 2>$null)
    if ($LASTEXITCODE -ne 0 -or $consumerRaw.Count -eq 0) {
        throw 'RABBIT_INSPECTION_FAILED: RabbitMQ consumer inspection failed.'
    }
    $consumers = @((($consumerRaw -join "`n") | ConvertFrom-Json))

    return Get-MembershipInterSegmentRabbitObservation `
        -Connections $connections `
        -Queues $rabbitQueues `
        -Consumers $consumers
}

function New-InterSegmentFailureState(
        [AllowNull()] [psobject] $CurrentState,
        [string] $ReasonCode,
        [string] $ReasonMessage) {
    return [pscustomobject][ordered]@{
        decision = 'FAIL'
        phase = 'FAILED'
        quietSince = if ($null -eq $CurrentState) { $null } else { $CurrentState.quietSince }
        nonEmptySince = if ($null -eq $CurrentState) { $null } else { $CurrentState.nonEmptySince }
        quietElapsedSeconds = if ($null -eq $CurrentState) { 0L } else {
            [long]$CurrentState.quietElapsedSeconds
        }
        nonEmptyElapsedSeconds = if ($null -eq $CurrentState) { 0L } else {
            [long]$CurrentState.nonEmptyElapsedSeconds
        }
        reasonCode = $ReasonCode
        reasonMessage = $ReasonMessage
    }
}

function Write-InterSegmentStabilitySample(
        [string] $Path,
        [datetimeoffset] $SampledAt,
        [string] $CompletedGroup,
        [datetimeoffset] $LatestHardCloseAt,
        [AllowNull()] [psobject] $RabbitObservation,
        [psobject] $GateState) {
    $row = [pscustomobject][ordered]@{
        sampledAt = $SampledAt.ToString('O')
        completedGroup = $CompletedGroup
        latestHardCloseAt = $LatestHardCloseAt.ToString('O')
        phase = $GateState.phase
        paymentReady = if ($null -eq $RabbitObservation) { '' } else {
            $RabbitObservation.paymentReady
        }
        paymentUnacked = if ($null -eq $RabbitObservation) { '' } else {
            $RabbitObservation.paymentUnacked
        }
        closingReady = if ($null -eq $RabbitObservation) { '' } else {
            $RabbitObservation.closingReady
        }
        closingUnacked = if ($null -eq $RabbitObservation) { '' } else {
            $RabbitObservation.closingUnacked
        }
        paymentDlqReady = if ($null -eq $RabbitObservation) { '' } else {
            $RabbitObservation.paymentDlqReady
        }
        paymentDlqUnacked = if ($null -eq $RabbitObservation) { '' } else {
            $RabbitObservation.paymentDlqUnacked
        }
        closingDlqReady = if ($null -eq $RabbitObservation) { '' } else {
            $RabbitObservation.closingDlqReady
        }
        closingDlqUnacked = if ($null -eq $RabbitObservation) { '' } else {
            $RabbitObservation.closingDlqUnacked
        }
        quietElapsedSeconds = $GateState.quietElapsedSeconds
        nonEmptyElapsedSeconds = $GateState.nonEmptyElapsedSeconds
        decision = $GateState.decision
        reasonCode = $GateState.reasonCode
    }
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $row | Export-Csv -LiteralPath $Path -NoTypeInformation -Encoding UTF8 -Append
    } else {
        $row | Export-Csv -LiteralPath $Path -NoTypeInformation -Encoding UTF8
    }
}

function Save-InterSegmentStabilityVerdict(
        [string] $Path,
        [string] $Verdict,
        [string] $CompletedGroup,
        [datetimeoffset] $WaitStartedAt,
        [datetimeoffset] $LatestHardCloseAt,
        [datetimeoffset] $MaximumWaitDeadline,
        [int] $QuietRequiredSeconds,
        [AllowNull()] [psobject] $GateState,
        [AllowNull()] [psobject] $RabbitObservation,
        [string] $ReasonCode,
        [string] $ReasonMessage) {
    [ordered]@{
        verdict = $Verdict
        completedGroup = $CompletedGroup
        waitStartedAt = $WaitStartedAt.ToString('O')
        latestHardCloseAt = $LatestHardCloseAt.ToString('O')
        quietStartedAt = if ($null -eq $GateState -or $null -eq $GateState.quietSince) {
            $null
        } else {
            ([datetimeoffset]$GateState.quietSince).ToString('O')
        }
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
        quietRequiredSeconds = $QuietRequiredSeconds
        maximumWaitDeadline = $MaximumWaitDeadline.ToString('O')
        lastRabbitSnapshot = $RabbitObservation
        reasonCode = $ReasonCode
        reasonMessage = $ReasonMessage
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Wait-InterSegmentStability(
        [string] $CompletedGroup,
        [string] $ScenarioOrdersCsvPath,
        [string] $OutputDirectory,
        [ValidateRange(60, 600)]
        [int] $QuietSeconds) {
    $waitStartedAt = [datetimeoffset]::UtcNow
    $samplesPath = Join-Path $OutputDirectory 'inter-segment-stability-samples.csv'
    $verdictPath = Join-Path $OutputDirectory 'inter-segment-stability-verdict.json'
    try {
        $latestHardCloseAt = Get-MembershipLatestHardCloseAt `
            -ScenarioOrdersCsvPath $ScenarioOrdersCsvPath
    } catch {
        $fallbackHorizon = $waitStartedAt
        $fallbackDeadline = $waitStartedAt.AddSeconds(2 * $QuietSeconds)
        Save-InterSegmentStabilityVerdict `
            -Path $verdictPath `
            -Verdict 'FAIL' `
            -CompletedGroup $CompletedGroup `
            -WaitStartedAt $waitStartedAt `
            -LatestHardCloseAt $fallbackHorizon `
            -MaximumWaitDeadline $fallbackDeadline `
            -QuietRequiredSeconds $QuietSeconds `
            -GateState $null `
            -RabbitObservation $null `
            -ReasonCode 'INVALID_SCENARIO_HORIZON' `
            -ReasonMessage $_.Exception.Message
        throw
    }
    $deadlineAnchor = if ($waitStartedAt -gt $latestHardCloseAt) {
        $waitStartedAt
    } else {
        $latestHardCloseAt
    }
    $maximumWaitDeadline = $deadlineAnchor.AddSeconds(2 * $QuietSeconds)
    $gateState = $null
    $rabbitObservation = $null

    while ($true) {
        $sampledAt = [datetimeoffset]::UtcNow
        $rabbitObservation = $null
        try {
            if ((Get-SourceFingerprint) -ne $sourceFingerprint) {
                throw "SOURCE_FINGERPRINT_CHANGED: Source fingerprint changed after group $CompletedGroup."
            }
            try {
                Assert-PortBoundary
            } catch {
                throw "APPLICATION_PORT_CHANGED: $($_.Exception.Message)"
            }

            $rabbitObservation = Get-RabbitInterSegmentObservation
            $gateState = Update-MembershipInterSegmentGateState `
                -SampledAt $sampledAt `
                -LatestHardCloseAt $latestHardCloseAt `
                -MaximumWaitDeadline $maximumWaitDeadline `
                -QuietSeconds $QuietSeconds `
                -CurrentState $gateState `
                -RabbitObservation $rabbitObservation

            if ($gateState.decision -ne 'FAIL') {
                try {
                    $redisQueues = Invoke-RestMethod -Method Get `
                        -Uri "$baseUrl/internal/test/membership-payments/loadtest-inspection/queues" `
                        -TimeoutSec 15
                } catch {
                    throw "REDIS_INSPECTION_FAILED: $($_.Exception.Message)"
                }
                foreach ($property in @(
                    'callbackReadySize', 'callbackProcessingSize',
                    'dirtySize', 'dirtyProcessingSize')) {
                    if ([long]$redisQueues.$property -ne 0L) {
                        throw "REDIS_QUEUE_NOT_EMPTY: Redis queue changed during the post-group stability window: $property=$($redisQueues.$property)"
                    }
                }

                $sql = @'
SELECT COUNT(*) FILTER (WHERE status IN (0, 1)),
       COUNT(*) FILTER (
           WHERE entitlement_resolution IS NULL
              OR entitlement_resolved_at IS NULL)
FROM membership_order
WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000039999;
'@
                $postgresFacts = @(& psql -w (Resolve-PostgresUrl) `
                    -v ON_ERROR_STOP=1 -A -t -F '|' -c $sql)
                if ($LASTEXITCODE -ne 0 -or $postgresFacts.Count -ne 1 -or
                        $postgresFacts[0].Trim() -ne '0|0') {
                    throw "POSTGRES_NOT_STABLE: PostgreSQL is not stable after group ${CompletedGroup}: $($postgresFacts -join ';')"
                }
            }
        } catch {
            $message = $_.Exception.Message
            $reasonCode = 'INTER_SEGMENT_GATE_FAILED'
            $reasonMessage = $message
            if ($message -match '^([A-Z][A-Z0-9_]+):\s*(.*)$') {
                $reasonCode = $Matches[1]
                $reasonMessage = $Matches[2]
            }
            $gateState = New-InterSegmentFailureState `
                -CurrentState $gateState `
                -ReasonCode $reasonCode `
                -ReasonMessage $reasonMessage
        }

        Write-InterSegmentStabilitySample `
            -Path $samplesPath `
            -SampledAt $sampledAt `
            -CompletedGroup $CompletedGroup `
            -LatestHardCloseAt $latestHardCloseAt `
            -RabbitObservation $rabbitObservation `
            -GateState $gateState

        if ($gateState.decision -eq 'PASS') {
            Save-InterSegmentStabilityVerdict `
                -Path $verdictPath `
                -Verdict 'PASS' `
                -CompletedGroup $CompletedGroup `
                -WaitStartedAt $waitStartedAt `
                -LatestHardCloseAt $latestHardCloseAt `
                -MaximumWaitDeadline $maximumWaitDeadline `
                -QuietRequiredSeconds $QuietSeconds `
                -GateState $gateState `
                -RabbitObservation $rabbitObservation `
                -ReasonCode '' `
                -ReasonMessage $gateState.reasonMessage
            return
        }
        if ($gateState.decision -eq 'FAIL') {
            Save-InterSegmentStabilityVerdict `
                -Path $verdictPath `
                -Verdict 'FAIL' `
                -CompletedGroup $CompletedGroup `
                -WaitStartedAt $waitStartedAt `
                -LatestHardCloseAt $latestHardCloseAt `
                -MaximumWaitDeadline $maximumWaitDeadline `
                -QuietRequiredSeconds $QuietSeconds `
                -GateState $gateState `
                -RabbitObservation $rabbitObservation `
                -ReasonCode $gateState.reasonCode `
                -ReasonMessage $gateState.reasonMessage
            throw "$($gateState.reasonCode): $($gateState.reasonMessage)"
        }

        $remaining = [Math]::Max(
            0, [Math]::Ceiling(($maximumWaitDeadline - $sampledAt).TotalSeconds))
        Write-Output (
            "STABILITY_HEARTBEAT group=$CompletedGroup phase=$($gateState.phase) " +
            "quietSeconds=$($gateState.quietElapsedSeconds) remainingSeconds=$remaining")
        Start-Sleep -Seconds 2
    }
}

function Get-LatestStateMachineTimingSignature(
    [string] $Path,
    [string] $ExpectedRunId,
    [int] $TailLines = 8192) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ''
    }

    $legacyRunMarker = "runId=$ExpectedRunId "
    $compactRunMarker = " r=$ExpectedRunId "
    $latest = Get-Content -LiteralPath $Path -Tail $TailLines |
        Where-Object {
            ($_.Contains($legacyRunMarker, [System.StringComparison]::Ordinal) -or
                $_.Contains($compactRunMarker, [System.StringComparison]::Ordinal)) -and
            $_.Contains(
                'event=membership_payment_operation_completed ',
                [System.StringComparison]::Ordinal)
        } |
        Select-Object -Last 1
    if ($null -eq $latest) {
        return ''
    }
    return [string] $latest
}

function Wait-TimingLogQuiescence(
    [string] $Path,
    [string] $ExpectedRunId,
    [int] $TimeoutSeconds = 120,
    [int] $StableSeconds = 10) {
    $deadline = [datetimeoffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastSignature = ''
    $stableSince = $null
    do {
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            $file = Get-Item -LiteralPath $Path
            # 正式收敛完成后观察本 Run ID 的最后一条计时记录，连续稳定可证明异步 Appender 已落盘。
            $signature = Get-LatestStateMachineTimingSignature `
                -Path $Path `
                -ExpectedRunId $ExpectedRunId
            if (-not [string]::IsNullOrWhiteSpace($signature) -and
                    $signature -eq $lastSignature) {
                if ($null -eq $stableSince) {
                    $stableSince = [datetimeoffset]::UtcNow
                }
            } else {
                $stableSince = $null
                $lastSignature = $signature
            }
            $stableFor = if ($null -eq $stableSince) {
                0
            } else {
                [Math]::Floor(([datetimeoffset]::UtcNow - $stableSince).TotalSeconds)
            }
            Write-Output (
                "TIMING_LOG_HEARTBEAT bytes=$($file.Length) " +
                "stateMachineEventPresent=$(-not [string]::IsNullOrWhiteSpace($signature)) " +
                "stableSeconds=$stableFor")
            if ($stableFor -ge $StableSeconds) {
                return
            }
        } else {
            Write-Output 'TIMING_LOG_HEARTBEAT waitingForFile=true'
        }
        Start-Sleep -Seconds 1
    } while ([datetimeoffset]::UtcNow -lt $deadline)
    throw "Timing log did not become stable within $TimeoutSeconds seconds: $Path"
}

function Assert-CompactTimingWarmup([int] $ExpectedOrderCount) {
    if (-not (Test-Path -LiteralPath $timingLogPath -PathType Leaf)) {
        throw 'Compact timing log was not created during formal warmup.'
    }
    $runMarker = " r=$RunId "
    $lines = @(Get-Content -LiteralPath $timingLogPath -Tail 8192 | Where-Object {
        $_.Contains('event=membership_payment_operation_completed v=2 ',
            [StringComparison]::Ordinal) -and
        $_.Contains($runMarker, [StringComparison]::Ordinal)
    })
    $counts = @{ ORDER_CREATE = 0; PAYMENT_ATTEMPT = 0 }
    foreach ($line in $lines) {
        $fields = @{}
        foreach ($match in [regex]::Matches(
                $line, '(?<key>[A-Za-z][A-Za-z0-9]*)=(?<value>[^\s]+)')) {
            $fields[$match.Groups['key'].Value] = $match.Groups['value'].Value
        }
        $operation = [string]$fields['op']
        $totalMs = 0D
        if (-not [double]::TryParse(
                [string]$fields['t'],
                [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$totalMs)) {
            throw 'Compact timing warmup contains an invalid total duration.'
        }
        if ($counts.ContainsKey($operation)) {
            $counts[$operation] += 1
            # 新分层计时增加 Redis、Rabbit 与事务字段后，长 Run ID 的完整低基数证据仍须容纳在单个 512 字节事件内。
            if ($totalMs -lt 1000D -and
                    [Text.Encoding]::UTF8.GetByteCount($line) -gt 512) {
                throw "Fast compact HTTP timing line exceeds 512 bytes: operation=$operation"
            }
            continue
        }
        $outcome = [string]$fields['out']
        $deliveryCount = if ($fields.ContainsKey('dc')) { [long]$fields['dc'] } else { 0L }
        if ($totalMs -lt 1000D -and $outcome -notin @('FAILED', 'NACKED') -and
                $deliveryCount -eq 0L) {
            throw "Warmup emitted an unexpected fast success outside the two HTTP operations: $operation"
        }
    }
    foreach ($operation in $counts.Keys) {
        if ($counts[$operation] -lt $ExpectedOrderCount) {
            throw "Compact timing warmup did not log every $operation operation."
        }
    }
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

function Save-State([string] $State, [string] $Wave, [string] $Message = '') {
    Write-AtomicJson -Path $statePath -Value ([ordered]@{
        phase = 'MILLISECOND_BOUNDARY'
        state = $State
        wave = $Wave
        message = $Message
        runId = $RunId
        updatedAt = [datetimeoffset]::UtcNow.ToString('O')
    })
}

function Merge-CsvFiles(
    [string[]] $Paths,
    [string] $Destination,
    [int] $ExpectedRows) {
    $encoding = [Text.UTF8Encoding]::new($false)
    $writer = [IO.StreamWriter]::new($Destination, $false, $encoding)
    $expectedHeader = $null
    $rowCount = 0
    try {
        foreach ($path in $Paths) {
            $reader = [IO.StreamReader]::new($path, $encoding, $true)
            try {
                $header = $reader.ReadLine()
                if ([string]::IsNullOrWhiteSpace($header)) {
                    throw "CSV has no header: $path"
                }
                if ($null -eq $expectedHeader) {
                    $expectedHeader = $header
                    $writer.WriteLine($header)
                } elseif ($header -cne $expectedHeader) {
                    throw "CSV headers differ during final merge: $path"
                }
                while (-not $reader.EndOfStream) {
                    $line = $reader.ReadLine()
                    if (-not [string]::IsNullOrWhiteSpace($line)) {
                        $writer.WriteLine($line)
                        $rowCount += 1
                    }
                }
            } finally {
                $reader.Dispose()
            }
        }
    } finally {
        $writer.Dispose()
    }
    if ($rowCount -ne $ExpectedRows) {
        throw "Merged CSV row count is $rowCount; expected $ExpectedRows."
    }
    return $rowCount
}

function Get-CsvDataRowCount([string] $Path) {
    $reader = [IO.StreamReader]::new($Path, [Text.Encoding]::UTF8, $true)
    $count = -1
    try {
        while ($null -ne $reader.ReadLine()) {
            $count += 1
        }
    } finally {
        $reader.Dispose()
    }
    return [Math]::Max(0, $count)
}

function Write-ResetOrderManifest(
    [string[]] $ScenarioPaths,
    [string] $Destination,
    [int] $ExpectedOrderCount) {
    $encoding = [Text.UTF8Encoding]::new($false)
    $writer = [IO.StreamWriter]::new($Destination, $false, $encoding)
    $unique = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    $count = 0
    try {
        $writer.Write('{"orderIds":[')
        foreach ($path in $ScenarioPaths) {
            $rows = @(Import-Csv -LiteralPath $path)
            foreach ($row in $rows) {
                $orderId = [string]$row.order_id
                if ($orderId -notmatch '^[A-Za-z0-9_-]{22}$' -or -not $unique.Add($orderId)) {
                    throw "Reset manifest contains an invalid or duplicate order ID: $orderId"
                }
                if ($count -gt 0) { $writer.Write(',') }
                $writer.Write('"')
                $writer.Write($orderId)
                $writer.Write('"')
                $count += 1
            }
            $rows = $null
        }
        $writer.Write(']}')
    } finally {
        $writer.Dispose()
    }
    if ($count -ne $ExpectedOrderCount) {
        throw "Reset manifest contains $count order IDs; expected $ExpectedOrderCount."
    }
    return $count
}

function Get-PreviousBoundaryResetFacts([string[]] $ScenarioCsvPaths) {
    if ($null -eq $ScenarioCsvPaths -or $ScenarioCsvPaths.Count -eq 0) {
        throw 'At least one previous boundary scenario manifest is required.'
    }
    $manifestOrderIds = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    $manifestUserIds = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    $sourceManifest = [Collections.Generic.List[object]]::new()
    foreach ($scenarioPath in $ScenarioCsvPaths) {
        if (-not (Test-Path -LiteralPath $scenarioPath -PathType Leaf)) {
            throw "Previous boundary scenario manifest does not exist: $scenarioPath"
        }
        $resolvedPath = [IO.Path]::GetFullPath($scenarioPath)
        $scenarioRows = @(Import-Csv -LiteralPath $resolvedPath)
        if ($scenarioRows.Count -eq 0) {
            throw "Previous boundary scenario manifest is empty: $resolvedPath"
        }
        foreach ($row in $scenarioRows) {
            $orderId = [string]$row.order_id
            $userId = [string]$row.user_id
            if ($orderId -notmatch '^[A-Za-z0-9_-]{22}$' -or
                    -not $manifestOrderIds.Add($orderId)) {
                throw "Previous boundary manifest contains an invalid or duplicate ordinal order ID: $orderId"
            }
            if ($userId -notmatch '^700000000000[0-7][0-9]{4}$' -or
                    -not $manifestUserIds.Add($userId)) {
                throw "Previous boundary manifest contains an invalid or duplicate fixed user ID: $userId"
            }
        }
        $sourceManifest.Add([pscustomobject]@{
            path = $resolvedPath
            rowCount = $scenarioRows.Count
            sha256 = (Get-FileHash -LiteralPath $resolvedPath -Algorithm SHA256).Hash.ToLowerInvariant()
        })
    }
    $manifestOrderCount = $manifestOrderIds.Count
    if ($manifestOrderCount -le 0 -or $manifestOrderCount -gt 80000 -or
            $manifestUserIds.Count -ne $manifestOrderCount) {
        throw "Previous boundary manifests contain an unsafe cardinality: orders=$manifestOrderCount users=$($manifestUserIds.Count)"
    }

    $factsSql = @'
SELECT
    COUNT(*),
    COUNT(DISTINCT login_identity_id),
    COUNT(*) FILTER (
      WHERE status = 0
        AND entitlement_resolution IS NULL
        AND entitlement_resolved_at IS NULL),
    COUNT(*) FILTER (
      WHERE status IN (2, 3, 4)
        AND entitlement_resolution IS NOT NULL
        AND entitlement_resolved_at IS NOT NULL),
    (SELECT COUNT(*)
       FROM membership_payment_callback callback
       JOIN membership_order callback_order ON callback_order.id = callback.order_id
      WHERE callback_order.login_identity_id
            BETWEEN 70000000000000000 AND 70000000000079999),
    (SELECT COUNT(*)
       FROM membership_payment_callback callback
       JOIN membership_order callback_order ON callback_order.id = callback.order_id
      WHERE callback_order.login_identity_id
            BETWEEN 70000000000000000 AND 70000000000079999
        AND (callback.resolution IS NULL OR callback.resolved_at IS NULL))
FROM membership_order
WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000079999;
'@
    $factsRaw = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 `
        -A -t -F '|' -c $factsSql)
    if ($LASTEXITCODE -ne 0 -or $factsRaw.Count -ne 1) {
        throw "Previous boundary database fact query failed: actual=$($factsRaw -join ';')"
    }
    $resetMode = Resolve-MembershipBoundaryResetMode `
        -ManifestOrderCount $manifestOrderCount `
        -DatabaseFacts $factsRaw[0].Trim()
    $expectedDatabaseFacts = $resetMode.expectedDatabaseFacts

    $orderIdSql = @'
SELECT RTRIM(TRANSLATE(ENCODE(id, 'base64'), '+/', '-_'), '=')
FROM membership_order
WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000079999
ORDER BY 1;
'@
    $databaseOrderIds = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 `
        -A -t -c $orderIdSql | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0 -or $databaseOrderIds.Count -ne $manifestOrderCount) {
        throw "Previous boundary database order enumeration failed: count=$($databaseOrderIds.Count)"
    }
    $databaseOrderIdSet = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    foreach ($orderId in $databaseOrderIds) {
        if ($orderId -notmatch '^[A-Za-z0-9_-]{22}$' -or
                -not $databaseOrderIdSet.Add($orderId)) {
            throw "Previous boundary database contains an invalid or duplicate order ID: $orderId"
        }
    }
    if (-not $databaseOrderIdSet.SetEquals($manifestOrderIds)) {
        throw 'Previous manifest and fixed-user database order sets differ under ordinal comparison.'
    }

    $callbackIdSql = @'
SELECT RTRIM(TRANSLATE(ENCODE(callback.id, 'base64'), '+/', '-_'), '=')
FROM membership_payment_callback callback
JOIN membership_order payment_order ON payment_order.id = callback.order_id
WHERE payment_order.login_identity_id BETWEEN 70000000000000000 AND 70000000000079999
ORDER BY 1;
'@
    $callbackIds = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 `
        -A -t -c $callbackIdSql | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0 -or $callbackIds.Count -ne $resetMode.callbackCount) {
        throw "Previous boundary callback enumeration failed: expected=$($resetMode.callbackCount) count=$($callbackIds.Count)"
    }
    $callbackIdSet = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    foreach ($callbackId in $callbackIds) {
        if ($callbackId -notmatch '^[A-Za-z0-9_-]{22}$' -or
                -not $callbackIdSet.Add($callbackId)) {
            throw "Previous boundary database contains an invalid or duplicate callback ID: $callbackId"
        }
    }

    return [pscustomobject]@{
        orderIds = @($databaseOrderIds)
        callbackIds = @($callbackIds)
        manifestOrderCount = $manifestOrderCount
        manifestUserCount = $manifestUserIds.Count
        expectedDatabaseFacts = $expectedDatabaseFacts
        actualDatabaseFacts = $factsRaw[0].Trim()
        resetMode = $resetMode.mode
        resetEndpointPath = $resetMode.endpointPath
        sources = @($sourceManifest)
    }
}

function Invoke-PreviousBoundaryExactReset([string[]] $ScenarioCsvPaths) {
    $jmeterProcesses = @(Get-CimInstance Win32_Process -ErrorAction Stop |
        Where-Object {
            $_.Name -match '^java(w)?\.exe$' -and
            $_.CommandLine -match '(?i)ApacheJMeter|jmeter'
        })
    if ($jmeterProcesses.Count -ne 0) {
        throw 'A JMeter process is still running; exact previous-run reset is forbidden.'
    }
    Assert-PortBoundary
    Assert-RabbitBoundaryBaseline

    $currentCountSql = @'
SELECT
    COUNT(*),
    (SELECT COUNT(*)
       FROM membership_payment_callback callback
       JOIN membership_order callback_order ON callback_order.id = callback.order_id
      WHERE callback_order.login_identity_id
            BETWEEN 70000000000000000 AND 70000000000079999)
FROM membership_order
WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000079999;
'@
    $currentCounts = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 `
        -A -t -F '|' -c $currentCountSql)
    if ($LASTEXITCODE -ne 0 -or $currentCounts.Count -ne 1) {
        throw 'Previous boundary reset resume inspection failed.'
    }
    if ($currentCounts.Trim() -eq '0|0') {
        # 一键清理会把权益置为全零；此处只确认订单和回调已清空，随后由统一 /prepare 恢复可执行 FREE 基线。
        Assert-RedisBoundaryBaseline
        [ordered]@{
            reusedExactReset = $true
            fixturePreparationRequired = $true
            postgresBaselineDeferredToPrepare = $true
            orderCount = 0
            callbackCount = 0
            verifiedAt = [datetimeoffset]::UtcNow.ToString('O')
        } | ConvertTo-Json | Set-Content -LiteralPath (
            Join-Path $outputRoot 'previous-reset-already-clean.json') -Encoding UTF8
        return
    }

    $resetFacts = Get-PreviousBoundaryResetFacts -ScenarioCsvPaths $ScenarioCsvPaths
    [ordered]@{
        comparison = 'StringComparer.Ordinal'
        manifestOrderCount = $resetFacts.manifestOrderCount
        manifestUserCount = $resetFacts.manifestUserCount
        sources = $resetFacts.sources
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (
        Join-Path $outputRoot 'previous-reset-source-manifest.json') -Encoding UTF8
    [ordered]@{
        expectedDatabaseFacts = $resetFacts.expectedDatabaseFacts
        actualDatabaseFacts = $resetFacts.actualDatabaseFacts
        resetMode = $resetFacts.resetMode
        resetEndpointPath = $resetFacts.resetEndpointPath
        orderSetEqualsManifest = $true
        verifiedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json | Set-Content -LiteralPath (
        Join-Path $outputRoot 'previous-reset-database-facts.json') -Encoding UTF8
    $previousCallbackPath = Join-Path $outputRoot 'previous-callback-ids.csv'
    if ($resetFacts.callbackIds.Count -eq 0) {
        '"callback_id"' | Set-Content -LiteralPath $previousCallbackPath -Encoding UTF8
    } else {
        @($resetFacts.callbackIds | ForEach-Object {
            [pscustomobject]@{ callback_id = $_ }
        }) | Export-Csv -LiteralPath $previousCallbackPath -NoTypeInformation -Encoding UTF8
    }

    $redisContainer = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
    $redisReset = Remove-MembershipBoundaryRedisOrderArtifacts `
        -Container $redisContainer `
        -OrderIds @($resetFacts.orderIds) `
        -CallbackIds @($resetFacts.callbackIds)
    $redisReset | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath (Join-Path $outputRoot 'previous-redis-reset.json') -Encoding UTF8

    $resetManifest = Join-Path $outputRoot 'previous-reset-order-ids.json'
    [void](Write-ResetOrderManifest `
        -ScenarioPaths @($ScenarioCsvPaths) `
        -Destination $resetManifest `
        -ExpectedOrderCount $resetFacts.manifestOrderCount)
    $reset = Invoke-RestMethod -Method Post `
        -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/$($resetFacts.resetEndpointPath)" `
        -ContentType 'application/json' `
        -InFile $resetManifest `
        -TimeoutSec 300
    if (-not $reset.prepared -or $reset.identityCount -ne 80000 -or
            $reset.profileCount -ne 80000 -or $reset.quotaCount -ne 80000 -or
            $reset.orderCount -ne 0 -or $reset.callbackCount -ne 0) {
        throw 'Previous boundary fixture reset did not produce a clean fixed-user baseline.'
    }
    Assert-PostgresBoundaryBaseline
    Assert-RedisBoundaryBaseline
    [ordered]@{
        comparison = 'StringComparer.Ordinal'
        sourceManifestOrderCount = $resetFacts.manifestOrderCount
        sourceManifestUserCount = $resetFacts.manifestUserCount
        deletedOrderCount = $resetFacts.manifestOrderCount
        deletedCallbackCount = $resetFacts.callbackIds.Count
        resetQuotaCount = $resetFacts.manifestUserCount
        currentFixedFixtureOrderCount = 0
        currentFixedFixtureCallbackCount = 0
        retainedFormalOrderCount = 0
        retainedFormalCallbackCount = 0
        redisReset = $redisReset
        applicationReset = $reset
        verifiedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (
        Join-Path $outputRoot 'previous-exact-reset-receipt.json') -Encoding UTF8
}

function Assert-ResumeBoundaryState([string[]] $ScenarioCsvPaths) {
    $startIndex = [Array]::IndexOf($allGroups, $StartGroupCode)
    $expectedRetainedFormalCount = [long]$startIndex * [long]$expectedSegmentOrders
    if ($startIndex -le 0 -or $expectedRetainedFormalCount -le 0L) {
        throw "Resumed execution requires a completed group before $StartGroupCode."
    }

    # 续跑只接受上一 Run 已完成且已裁决的精确订单集合，禁止清理或重新准备已完成区段。
    $resumeFacts = Get-PreviousBoundaryResetFacts -ScenarioCsvPaths $ScenarioCsvPaths
    if ([long]$resumeFacts.manifestOrderCount -ne $expectedRetainedFormalCount -or
            [long]$resumeFacts.manifestUserCount -ne $expectedRetainedFormalCount -or
            [long]$resumeFacts.callbackIds.Count -ne $expectedRetainedFormalCount -or
            [string]$resumeFacts.resetMode -ne 'TERMINAL_RESOLVED') {
        throw "Resume evidence does not match the completed groups before ${StartGroupCode}: expected=$expectedRetainedFormalCount orders=$($resumeFacts.manifestOrderCount) callbacks=$($resumeFacts.callbackIds.Count) state=$($resumeFacts.resetMode)"
    }
    Assert-SegmentBoundaryGate -GroupCode $StartGroupCode `
        -ExpectedRetainedFormalCount ([int]$expectedRetainedFormalCount)
    [ordered]@{
        startGroupCode = $StartGroupCode
        expectedRetainedFormalCount = $expectedRetainedFormalCount
        actualRetainedOrderCount = [long]$resumeFacts.manifestOrderCount
        actualRetainedCallbackCount = [long]$resumeFacts.callbackIds.Count
        databaseState = [string]$resumeFacts.resetMode
        sourceManifests = @($resumeFacts.sources)
        verifiedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (
        Join-Path $outputRoot 'resume-boundary-receipt.json') -Encoding UTF8
}

function Get-SegmentContract([string] $GroupCode) {
    $fileName = if ($RunScale -eq 'PERFORMANCE_40K') {
        'membership-millisecond-boundary-groups.csv'
    } else {
        'membership-millisecond-boundary-groups-80k.csv'
    }
    $path = Join-Path $repositoryRoot "loadtest\input\$fileName"
    $rows = @(Import-Csv -LiteralPath $path | Where-Object groupCode -eq $GroupCode)
    if ($rows.Count -ne 1 -or [int]$rows[0].userCount -ne $expectedSegmentOrders) {
        throw "Fixed segment contract is missing or invalid: $RunScale/$GroupCode"
    }
    return $rows[0]
}

function Assert-SegmentBoundaryGate(
        [string] $GroupCode,
        [int] $ExpectedRetainedFormalCount) {
    $contract = Get-SegmentContract -GroupCode $GroupCode
    $firstUserId = [long]$contract.firstUserId
    $lastUserId = $firstUserId + $expectedSegmentOrders - 1L
    $fixtureFirstUserId = 70000000000000000L
    $retainedLastUserId = $firstUserId - 1L
    $sql = @"
SELECT
  (SELECT COUNT(*) FROM membership_order
    WHERE login_identity_id BETWEEN $firstUserId AND $lastUserId),
  (SELECT COUNT(*) FROM membership_payment_callback callback
    JOIN membership_order payment_order ON payment_order.id = callback.order_id
      WHERE payment_order.login_identity_id BETWEEN $firstUserId AND $lastUserId),
  (SELECT COUNT(*) FROM user_membership_quota
    WHERE login_identity_id BETWEEN $firstUserId AND $lastUserId
      AND membership_tier = 0
      AND quota_balance_minor = 5000
      AND quota_period_started_at IS NULL
      AND quota_period_ends_at IS NOT NULL
      AND membership_expires_at IS NULL),
  (SELECT COUNT(*) FROM membership_order
    WHERE login_identity_id BETWEEN $fixtureFirstUserId AND $retainedLastUserId),
  (SELECT COUNT(*) FROM membership_payment_callback callback
    JOIN membership_order payment_order ON payment_order.id = callback.order_id
      WHERE payment_order.login_identity_id BETWEEN $fixtureFirstUserId AND $retainedLastUserId),
  (SELECT COUNT(*) FROM membership_order
    WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000079999),
  (SELECT COUNT(*) FROM membership_payment_callback callback
    JOIN membership_order payment_order ON payment_order.id = callback.order_id
      WHERE payment_order.login_identity_id BETWEEN 70000000000000000 AND 70000000000079999);
"@
    $raw = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 -A -t -F '|' -c $sql)
    if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1) {
        throw "Segment boundary database gate failed to execute: $GroupCode"
    }
    $facts = @($raw[0].Trim().Split('|') | ForEach-Object { [long]$_ })
    $expected = @(
        0L, 0L, [long]$expectedSegmentOrders,
        [long]$ExpectedRetainedFormalCount, [long]$ExpectedRetainedFormalCount,
        [long]$ExpectedRetainedFormalCount, [long]$ExpectedRetainedFormalCount)
    if (($facts -join '|') -ne ($expected -join '|')) {
        throw "Segment boundary database gate is invalid for ${GroupCode}: $($facts -join '|')"
    }
    Assert-RabbitBoundaryBaseline
    $queues = Invoke-RestMethod -Method Get `
        -Uri "$baseUrl/internal/test/membership-payments/loadtest-inspection/queues" `
        -TimeoutSec 15
    foreach ($property in @(
            'callbackReadySize', 'callbackProcessingSize', 'dirtySize', 'dirtyProcessingSize')) {
        if ([long]$queues.$property -ne 0L) {
            throw "Segment boundary Redis queue is not empty: $property=$($queues.$property)"
        }
    }
}

function New-SegmentWarmupRunId([string] $GroupCode, [int] $Attempt) {
    $candidate = "$RunId-warmup-$GroupCode-a$Attempt"
    if ($candidate.Length -le 128) { return $candidate }
    $suffix = (Get-TextSha256 $candidate).Substring(0, 16)
    return $candidate.Substring(0, 111) + '-' + $suffix
}

function Get-HttpEvidenceRunId(
        [string] $GroupCode,
        [ValidateSet('WARMUP', 'FORMAL')] [string] $ExecutionPhase,
        [int] $Attempt = 0) {
    if ($ExecutionPhase -eq 'FORMAL') { return $TimingLogRunId }
    $candidate = "$TimingLogRunId-warmup-$GroupCode-a$Attempt"
    if ($Attempt -notin @(1, 2) -or $candidate.Length -gt 128) {
        throw "TEST_INVALID_HTTP_EVIDENCE_IDENTITY: invalid same-PID warmup evidence identity for $GroupCode/attempt-$Attempt."
    }
    return $candidate
}

function Wait-WarmupSamplerDrainEvidence([int] $TimeoutSeconds = 60) {
    $warmupRoot = Join-Path (Join-Path (Join-Path $outputRoot `
        $script:currentGroupCode) 'warmup') "attempt-$($script:currentWarmupAttempt)"
    $queueSamplesPath = Join-Path $warmupRoot 'queue-drain-gate-samples.csv'
    $deadline = [datetimeoffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $zeroSamples = [Collections.Generic.List[object]]::new()
    $lastQueueState = 'not-sampled'
    do {
        $rabbitArguments = @('list_queues', '--formatter', 'json', 'name',
            'messages_ready', 'messages_unacknowledged')
        $rabbitRaw = @(& docker exec rabbitmq1 rabbitmqctl @rabbitArguments 2>$null)
        if ($LASTEXITCODE -ne 0 -or $rabbitRaw.Count -eq 0) {
            throw 'TEST_INVALID_RABBIT_OBSERVATION: direct broker queue inspection failed during warmup drain.'
        }
        try {
            $rabbitQueues = @(($rabbitRaw -join "`n") | ConvertFrom-Json)
        } catch {
            throw "TEST_INVALID_RABBIT_OBSERVATION: direct broker queue inspection returned invalid JSON: $($_.Exception.Message)"
        }
        $paymentQueue = @($rabbitQueues | Where-Object name -eq `
            'membership.payment.check.queue')
        $closingQueue = @($rabbitQueues | Where-Object name -eq `
            'membership.closing.check.queue')
        if ($paymentQueue.Count -ne 1 -or $closingQueue.Count -ne 1) {
            throw 'TEST_INVALID_RABBIT_OBSERVATION: direct broker inspection did not return both membership queues.'
        }

        try {
            $redisQueues = Invoke-RestMethod -Method Get `
                -Uri "$baseUrl/internal/test/membership-payments/loadtest-inspection/queues" `
                -TimeoutSec 15
        } catch {
            throw "TEST_INVALID_REDIS_OBSERVATION: direct warmup queue inspection failed: $($_.Exception.Message)"
        }
        $sample = [pscustomobject][ordered]@{
            sampledAt = [datetimeoffset]::UtcNow.ToString('O')
            segment = "$($script:currentGroupCode)/attempt-$($script:currentWarmupAttempt)"
            callbackReadySize = [long]$redisQueues.callbackReadySize
            callbackProcessingSize = [long]$redisQueues.callbackProcessingSize
            dirtySize = [long]$redisQueues.dirtySize
            dirtyProcessingSize = [long]$redisQueues.dirtyProcessingSize
            rabbitPaymentReady = [long]$paymentQueue[0].messages_ready
            rabbitPaymentUnacked = [long]$paymentQueue[0].messages_unacknowledged
            rabbitClosingReady = [long]$closingQueue[0].messages_ready
            rabbitClosingUnacked = [long]$closingQueue[0].messages_unacknowledged
        }
        $queueFieldNames = @(
            'callbackReadySize', 'callbackProcessingSize',
            'dirtySize', 'dirtyProcessingSize',
            'rabbitPaymentReady', 'rabbitPaymentUnacked',
            'rabbitClosingReady', 'rabbitClosingUnacked')
        $nonZeroFields = @($queueFieldNames | Where-Object {
            [long]$sample.$_ -ne 0L
        })
        $lastQueueState = @($queueFieldNames | ForEach-Object {
            "$_=$($sample.$_)"
        }) -join ','
        if ($nonZeroFields.Count -eq 0) {
            $zeroSamples.Add($sample)
            if ($zeroSamples.Count -ge 3) {
                @($zeroSamples) | Export-Csv -LiteralPath $queueSamplesPath `
                    -NoTypeInformation -Encoding UTF8
                return $queueSamplesPath
            }
        } else {
            $zeroSamples.Clear()
        }
        Start-Sleep -Milliseconds 500
    } while ([datetimeoffset]::UtcNow -lt $deadline)
    throw "WARMUP_QUEUE_DRAIN_TIMEOUT: direct Redis and RabbitMQ queues did not remain empty for three samples within ${TimeoutSeconds}s: $lastQueueState"
}

function Invoke-SegmentWarmupAttempt(
        [string] $GroupCode,
        [int] $Attempt,
        [string] $WarmupRunId) {
    $script:currentGroupCode = $GroupCode
    $script:currentWarmupAttempt = $Attempt
    $script:currentOriginStage = 'WARMUP_WAVE'
    Save-State 'WARMING' "$GroupCode/attempt-$Attempt"
    $warmupStartedAt = [datetimeoffset]::UtcNow
    $warmupHttpEvidenceRunId = Get-HttpEvidenceRunId `
        -GroupCode $GroupCode -ExecutionPhase 'WARMUP' -Attempt $Attempt
    # 子脚本会输出 psql 和诊断提示；这些文本不能混入本函数唯一的结构化返回值。
    $null = & (Join-Path $PSScriptRoot 'Invoke-MembershipMillisecondBoundaryWave.ps1') `
        -GroupCode $GroupCode -RunId $WarmupRunId -OutputRoot $outputRoot `
        -HttpEvidenceRunId $warmupHttpEvidenceRunId `
        -RunScale $RunScale -ExecutionPhase 'WARMUP' -WarmupAttempt $Attempt `
        -SourceFingerprint $sourceFingerprint -HostName $HostName -Port $Port -Protocol $Protocol `
        -CreationConcurrency $CreationConcurrency -HttpConcurrency $HttpConcurrency `
        -PaymentConcurrency $PaymentConcurrency
    if ($LASTEXITCODE -ne 0) { throw "Boundary warmup failed: $GroupCode/attempt-$Attempt" }
    Assert-PostgresIdentityUnchanged

    $warmupRoot = Join-Path (Join-Path (Join-Path $outputRoot $GroupCode) 'warmup') `
        "attempt-$Attempt"
    Copy-Item -LiteralPath (Join-Path $warmupRoot 'verdict.json') `
        -Destination (Join-Path $warmupRoot 'functional-verdict.json') -Force
    $script:currentOriginStage = 'WARMUP_FOCUSED_REPORT'
    $null = & (Join-Path $PSScriptRoot 'New-MembershipPaymentFocusedTimingReport.ps1') `
        -LogPath $timingLogPath `
        -RunId $WarmupRunId `
        -LogRunId $TimingLogRunId `
        -ScenarioOrdersCsvPath (Join-Path $warmupRoot 'scenario-orders.csv') `
        -OutputDirectory $warmupRoot `
        -TopSlowCount 100 `
        -MinimumCompletedAtEpochMs $warmupStartedAt.ToUnixTimeMilliseconds()
    $script:currentOriginStage = 'WARMUP_QUEUE_DRAIN'
    $queueSamplesPath = Wait-WarmupSamplerDrainEvidence
    $script:currentOriginStage = 'WARMUP_STABILITY_REPORT'
    $null = & (Join-Path $PSScriptRoot 'New-MembershipWarmupStabilityReport.ps1') `
        -RunId $WarmupRunId `
        -HttpLogRunId $warmupHttpEvidenceRunId `
        -GroupCode $GroupCode `
        -ScenarioOrdersCsvPath (Join-Path $warmupRoot 'scenario-orders.csv') `
        -HttpEventsLogPath $httpTimingLogPath `
        -FocusedEventsCsvPath (Join-Path $warmupRoot 'membership-payment-focused-events.csv') `
        -RequestResultsCsvPath (Join-Path $warmupRoot 'request-results.csv') `
        -QueueSamplesCsvPath $queueSamplesPath `
        -OutputDirectory $warmupRoot `
        -AllowEventsOutsideScenarioManifest
    $stabilityVerdict = Get-Content -Raw -LiteralPath (
        Join-Path $warmupRoot 'verdict.json') | ConvertFrom-Json
    if (-not [bool]$stabilityVerdict.functionalPassed) {
        return $stabilityVerdict
    }
    $script:currentOriginStage = 'WARMUP_GOLDEN_REPORT'
    $null = & (Join-Path $PSScriptRoot `
        'New-MembershipOrderCreateGoldenBaselineComparison.ps1') `
        -RunId $WarmupRunId `
        -HttpLogRunId $warmupHttpEvidenceRunId `
        -GroupCode $GroupCode `
        -ExecutionPhase 'WARMUP' `
        -ScenarioOrdersCsvPath (Join-Path $warmupRoot 'scenario-orders.csv') `
        -HttpEventsLogPath $httpTimingLogPath `
        -FocusedSummaryCsvPath (Join-Path $warmupRoot `
            'membership-payment-focused-operation-summary.csv') `
        -GoldenBaselineRunId $GoldenBaselineRunId `
        -GoldenBaselineEvidenceRoot $GoldenBaselineEvidenceRoot `
        -OutputDirectory $warmupRoot `
        -AllowEventsOutsideScenarioManifest
    return $stabilityVerdict
}

function Get-SegmentCallbackIds([string] $GroupCode) {
    $contract = Get-SegmentContract -GroupCode $GroupCode
    $firstUserId = [long]$contract.firstUserId
    $lastUserId = $firstUserId + $expectedSegmentOrders - 1L
    $sql = @"
SELECT RTRIM(TRANSLATE(ENCODE(callback.id, 'base64'), '+/', '-_'), '=')
FROM membership_payment_callback callback
JOIN membership_order payment_order ON payment_order.id = callback.order_id
WHERE payment_order.login_identity_id BETWEEN $firstUserId AND $lastUserId
ORDER BY callback.id;
"@
    $ids = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 -A -t -c $sql |
        ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0 -or $ids.Count -ne $expectedWarmupOrders) {
        throw "Warmup callback manifest is not exact for ${GroupCode}: $($ids.Count)"
    }
    return $ids
}

function Reset-SegmentWarmup(
        [string] $GroupCode,
        [int] $Attempt,
        [string] $WarmupRunId,
        [int] $ExpectedRetainedFormalCount) {
    $warmupRoot = Join-Path (Join-Path (Join-Path $outputRoot $GroupCode) 'warmup') `
        "attempt-$Attempt"
    $scenarioRows = @(Import-Csv -LiteralPath (Join-Path $warmupRoot 'scenario-orders.csv'))
    $orderIds = @($scenarioRows | ForEach-Object { [string]$_.order_id })
    $userIds = @($scenarioRows | ForEach-Object { [string]$_.user_id })
    $uniqueOrderIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $uniqueUserIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($orderId in $orderIds) { [void]$uniqueOrderIds.Add($orderId) }
    foreach ($userId in $userIds) { [void]$uniqueUserIds.Add($userId) }
    if ($orderIds.Count -ne $expectedWarmupOrders -or
            $uniqueOrderIds.Count -ne $expectedWarmupOrders -or
            $userIds.Count -ne $expectedWarmupOrders -or
            $uniqueUserIds.Count -ne $expectedWarmupOrders) {
        throw "Warmup order manifest is not exact for ${GroupCode}: $($orderIds.Count)"
    }
    $callbackIds = @(Get-SegmentCallbackIds -GroupCode $GroupCode)
    $redisContainer = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
    $redisEnvironment = if ($env:MEMBERSHIP_PAYMENT_REDIS_ENVIRONMENT) {
        $env:MEMBERSHIP_PAYMENT_REDIS_ENVIRONMENT
    } else { 'local' }
    $redisReceipt = Remove-MembershipBoundaryRedisExactWarmupArtifacts `
        -Container $redisContainer -OrderIds $orderIds -CallbackIds $callbackIds `
        -Environment $redisEnvironment
    $redisReceipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (
        Join-Path $warmupRoot 'redis-reset-receipt.json') -Encoding UTF8

    $resetBody = [ordered]@{
        runScale = $RunScale
        groupCode = $GroupCode
        warmupRunId = $WarmupRunId
        orderIds = $orderIds
    } | ConvertTo-Json -Depth 4 -Compress
    $reset = Invoke-RestMethod -Method Post `
        -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/segment-warmup-reset" `
        -ContentType 'application/json' -Body $resetBody -TimeoutSec 180
    if ([long]$reset.deletedOrderCount -ne $expectedWarmupOrders -or
            [long]$reset.deletedCallbackCount -ne $expectedWarmupOrders -or
            [long]$reset.resetQuotaCount -ne $expectedWarmupOrders -or
            [long]$reset.currentGroupOrderCount -ne 0L -or
            [long]$reset.currentGroupCallbackCount -ne 0L -or
            [long]$reset.retainedFormalOrderCount -ne $ExpectedRetainedFormalCount -or
            [long]$reset.retainedFormalCallbackCount -ne $ExpectedRetainedFormalCount) {
        throw "Segment warmup reset returned an invalid receipt: $GroupCode/attempt-$Attempt"
    }
    $receiptPath = Join-Path $warmupRoot 'warmup-reset-receipt.json'
    $reset | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $receiptPath -Encoding UTF8
    Copy-Item -LiteralPath $receiptPath -Destination (
        Join-Path (Join-Path $outputRoot $GroupCode) 'warmup-reset-receipt.json') -Force
    Assert-SegmentBoundaryGate -GroupCode $GroupCode `
        -ExpectedRetainedFormalCount $ExpectedRetainedFormalCount
    Assert-PostgresIdentityUnchanged
    Get-ChildItem -LiteralPath $tokenRoot -Filter "$WarmupRunId-*.csv" `
        -File -ErrorAction SilentlyContinue | Remove-Item -Force
    return [datetimeoffset]::UtcNow
}

function Invoke-SegmentSameScaleWarmup(
        [string] $GroupCode,
        [int] $ExpectedRetainedFormalCount) {
    $attemptResults = [Collections.Generic.List[object]]::new()
    for ($warmupAttempt = 1; $warmupAttempt -le 2; $warmupAttempt += 1) {
        $warmupRunId = New-SegmentWarmupRunId -GroupCode $GroupCode -Attempt $warmupAttempt
        $verdict = $null
        $attemptFailure = $null
        try {
            $verdict = Invoke-SegmentWarmupAttempt `
                -GroupCode $GroupCode -Attempt $warmupAttempt -WarmupRunId $warmupRunId
        } catch {
            $attemptFailure = $_
            $attemptFailureOriginStage = $script:currentOriginStage
        }
        if ($null -ne $attemptFailure -and
                $attemptFailure.Exception.Message -like 'TEST_INVALID_POSTGRES:*') {
            throw $attemptFailure
        }
        $cleanupCompletedAt = $null
        $cleanupFailure = $null
        try {
            # 功能或报告失败仍必须使用本轮清单尝试精确复位；复位失败会保留双重原因并终止 Run。
            $script:currentOriginStage = 'WARMUP_EXACT_CLEANUP'
            $cleanupCompletedAt = Reset-SegmentWarmup `
                -GroupCode $GroupCode -Attempt $warmupAttempt -WarmupRunId $warmupRunId `
                -ExpectedRetainedFormalCount $ExpectedRetainedFormalCount
        } catch {
            $cleanupFailure = $_
        }
        if ($null -ne $attemptFailure) {
            if ($null -ne $cleanupFailure) {
                throw "WARMUP_INTERRUPTED: $($attemptFailure.Exception.Message); exact cleanup also failed: $($cleanupFailure.Exception.Message)"
            }
            $script:currentOriginStage = $attemptFailureOriginStage
            throw $attemptFailure
        }
        if ($null -ne $cleanupFailure) { throw $cleanupFailure }
        $attemptResults.Add([pscustomobject]@{
            attempt = $warmupAttempt
            warmupRunId = $warmupRunId
            verdict = [string]$verdict.verdict
            functionalPassed = [bool]$verdict.functionalPassed
            contractPerformancePassed = [bool]$verdict.contractPerformancePassed
            performanceClassification = [string]$verdict.performanceClassification
            failureCode = [string]$verdict.failureCode
            cleanupCompletedAt = $cleanupCompletedAt
        })
        if (-not [bool]$verdict.functionalPassed) {
            if ([string]$verdict.failureCode -in @(
                    'WARMUP_HTTP_EVIDENCE_INCOMPLETE',
                    'WARMUP_FOCUSED_EVIDENCE_INCOMPLETE')) {
                $script:currentOriginStage = 'WARMUP_STABILITY_REPORT'
                throw "TEST_INVALID_WARMUP_EVIDENCE: $GroupCode/attempt-$warmupAttempt failed evidence collection with code $($verdict.failureCode)."
            }
            throw "WARMUP_FUNCTIONAL_FAILURE: $GroupCode/attempt-$warmupAttempt did not satisfy functional, reliability or convergence gates."
        }
        # 两次预热都用于建立真实运行态和诊断；性能结果必须保留，但不能阻断正式段。
        if ($warmupAttempt -eq 2) {
            return [pscustomobject]@{
                attempt = 2
                warmupRunId = $warmupRunId
                cleanupCompletedAt = $cleanupCompletedAt
                warmup2ContractPerformancePassed = [bool]$verdict.contractPerformancePassed
                warmup2PerformanceClassification = [string]$verdict.performanceClassification
                attempts = @($attemptResults)
            }
        }
    }
    throw "PREHEAT_INSUFFICIENT: $GroupCode did not complete exactly two same-scale warmups."
}

if ($Port -ne 6655) { throw 'The suite is fixed to local port 6655.' }
foreach ($command in @('git', 'jmeter', 'psql', 'docker', 'java', 'jcmd')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is unavailable: $command"
    }
}
New-Item -ItemType Directory -Force -Path $outputRoot, $tokenRoot | Out-Null
$gitHead = (& git rev-parse HEAD 2>$null).Trim()
$jarPath = (Resolve-Path (Join-Path $repositoryRoot `
    'ai-temperate-web\target\ai-temperate-web-0.0.1-SNAPSHOT.jar')).Path
$jarSha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($jarSha256 -ne $expectedJarSha256) {
    throw "TEST_INVALID_ARTIFACT: fixed JAR SHA-256 mismatch: $jarSha256"
}
$javaVersion = (& java -version 2>&1 | Out-String).Trim()
$sourceFingerprint = Get-SourceFingerprint
$startedAt = [datetimeoffset]::UtcNow
$runManifest = [ordered]@{
    runId = $RunId
    gitHead = $gitHead
    sourceFingerprint = $sourceFingerprint
    jarPath = $jarPath
    jarSha256 = $jarSha256
    javaVersion = $javaVersion
    previousScenarioOrdersCsvPaths = @($PreviousScenarioOrdersCsvPath | ForEach-Object {
        [IO.Path]::GetFullPath($_)
    })
    timingForceLogOperations = @('ORDER_CREATE', 'PAYMENT_ATTEMPT')
    orderCreateHttpEvidenceContract = [ordered]@{
        enabledEnvironment = 'MEMBERSHIP_ORDER_CREATE_HTTP_EVIDENCE_ENABLED'
        logPathEnvironment = 'MEMBERSHIP_ORDER_CREATE_HTTP_LOG_PATH'
        logPath = $httpTimingLogPath
    }
    creationConcurrency = $CreationConcurrency
    httpConcurrency = $HttpConcurrency
    paymentConcurrency = $PaymentConcurrency
    redisWriteBatchSize = $RedisWriteBatchSize
    redisWriteLaneCount = $RedisWriteLaneCount
    redisWriteMaximumInflight = $RedisWriteMaximumInflight
    warmupOrderCount = $WarmupOrderCount
    perSegmentWarmupOrderCount = $expectedWarmupOrders
    maximumWarmupAttempts = 2
    mode = 'loadtest-realtime'
    port = 6655
    precheck = "PT$($PrecheckSeconds)S"
    postgresStability = "PT$($PostgresStabilitySeconds)S"
    existingPostgresStabilityGatePath = if (
            [string]::IsNullOrWhiteSpace($ExistingPostgresStabilityGatePath)) {
        $null
    } else { [IO.Path]::GetFullPath($ExistingPostgresStabilityGatePath) }
    interSegment = "PT$($InterSegmentSeconds)S"
    connectionContract = [ordered]@{
        postgresMaxConnections = $PostgresMaxConnections
        hikariMaximumPoolSize = $HikariMaximumPoolSize
        hikariMinimumIdle = $HikariMinimumIdle
        maximumNavicatConnections = $MaximumNavicatConnections
    }
    pending = 'PT5M'
    closing = 'PT5M'
    runScale = $RunScale
    fixtureUsers = 80000
    users = $expectedRunOrders
    orders = $expectedRunOrders
    groups = $groups
        directConcurrencyCanary = [bool]$DirectConcurrencyCanary
        goldenBaselineRunId = $GoldenBaselineRunId
        goldenBaselineEvidenceRoot = [IO.Path]::GetFullPath($GoldenBaselineEvidenceRoot)
        timingLogRunId = $TimingLogRunId
        stopAfterWarmupSequence = [bool]$StopAfterWarmupSequence
    startGroupCode = $StartGroupCode
    skipInitialGates = [bool]$SkipInitialGates
    suiteStartedAt = $startedAt.ToString('O')
    formalStartedAt = $null
    formalStartedAtEpochMs = $null
}
$runManifest | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath (Join-Path $outputRoot 'run-manifest.json') -Encoding UTF8

try {
    if ($SkipInitialGates) {
        Save-State 'RESUMING' $StartGroupCode
        Import-PostgresStabilityBaseline -Path $ExistingPostgresStabilityGatePath
        Assert-ResumeBoundaryState `
            -ScenarioCsvPaths @($PreviousScenarioOrdersCsvPath | ForEach-Object {
                [IO.Path]::GetFullPath($_)
            })
        Assert-PostgresIdentityUnchanged
    } else {
        if (-not [string]::IsNullOrWhiteSpace($ExistingPostgresStabilityGatePath)) {
            Save-State 'PRECHECK' 'POSTGRES_STABILITY_REUSE'
            Import-PostgresStabilityBaseline -Path $ExistingPostgresStabilityGatePath
        }
        Save-State 'RESETTING' 'PREVIOUS_RUN'
        Invoke-PreviousBoundaryExactReset `
            -ScenarioCsvPaths @($PreviousScenarioOrdersCsvPath | ForEach-Object {
                [IO.Path]::GetFullPath($_)
            })

        Save-State 'PREPARING' 'FIXTURE'
        $prepared = Invoke-RestMethod -Method Post `
            -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/prepare" `
            -TimeoutSec 180
        if (-not $prepared.prepared -or $prepared.identityCount -ne 80000 `
            -or $prepared.profileCount -ne 80000 -or $prepared.quotaCount -ne 80000 `
            -or $prepared.orderCount -ne 0 -or $prepared.callbackCount -ne 0) {
            throw 'The persistent 80,000-user fixture is not at a clean FREE baseline.'
        }
        Assert-PortBoundary
        Assert-PostgresBoundaryBaseline
        Assert-RedisBoundaryBaseline
        Assert-RabbitBoundaryBaseline

        if (-not [string]::IsNullOrWhiteSpace($ExistingPostgresStabilityGatePath)) {
            Save-State 'PRECHECK' 'POSTGRES_STABILITY_REUSE'
            Assert-PostgresIdentityUnchanged
            Assert-PortBoundary
            Assert-PostgresBoundaryBaseline
            Assert-RedisBoundaryBaseline
            Assert-RabbitBoundaryBaseline
            Save-RedisPerformanceSnapshot -Name 'precheck-end'
            Save-ApplicationPerformanceSnapshot -Name 'precheck-end'
        } else {
            Save-State 'PRECHECK' 'POSTGRES_STABILITY'
            Assert-PostgresProcessStability -Seconds $PostgresStabilitySeconds
            if ((Get-SourceFingerprint) -ne $sourceFingerprint) {
                throw 'Source fingerprint changed during the PostgreSQL stability gate.'
            }
            Assert-PortBoundary
            Assert-PostgresBoundaryBaseline
            Assert-RedisBoundaryBaseline
            Assert-RabbitBoundaryBaseline
            Save-RedisPerformanceSnapshot -Name 'precheck-end'
            Save-ApplicationPerformanceSnapshot -Name 'precheck-end'
        }
    }

    # 状态门禁替代固定一百二十秒空等；真实同规模预热在每个区段内部执行。
    $redisContainer = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
    $slowlogReset = @(Invoke-MembershipBoundaryRedisCli `
        -Container $redisContainer -Arguments @('SLOWLOG', 'RESET'))
    if ($slowlogReset.Count -ne 1 -or [string]$slowlogReset[0] -ne 'OK') {
        throw 'Redis SLOWLOG reset failed before the same-scale warmup sequence.'
    }
    $formalStartedAt = [datetimeoffset]::UtcNow
    $runManifest['formalStartedAt'] = $formalStartedAt.ToString('O')
    $runManifest['formalStartedAtEpochMs'] = $formalStartedAt.ToUnixTimeMilliseconds()
    $runManifest | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath (Join-Path $outputRoot 'run-manifest.json') -Encoding UTF8
    Save-RedisPerformanceSnapshot -Name 'baseline'
    Save-ApplicationPerformanceSnapshot -Name 'baseline'

    $segmentPerformanceFailures = [Collections.Generic.List[string]]::new()
    for ($groupIndex = 0; $groupIndex -lt $groups.Count; $groupIndex += 1) {
        $group = $groups[$groupIndex]
        $fixedGroupIndex = [Array]::IndexOf($allGroups, $group)
        $expectedRetainedFormalCount = $fixedGroupIndex * $expectedSegmentOrders
        Assert-SegmentBoundaryGate -GroupCode $group `
            -ExpectedRetainedFormalCount $expectedRetainedFormalCount
        $warmupResult = Invoke-SegmentSameScaleWarmup `
            -GroupCode $group `
            -ExpectedRetainedFormalCount $expectedRetainedFormalCount
        Save-RedisPerformanceSnapshot -Name "warmup-$group-attempt-$($warmupResult.attempt)"

        if ($StopAfterWarmupSequence) {
            $warmupAttemptRoot = Join-Path (Join-Path (Join-Path $outputRoot $group) 'warmup') `
                "attempt-$($warmupResult.attempt)"
            $cleanupReceiptPath = Join-Path $warmupAttemptRoot 'warmup-reset-receipt.json'
            $warmupVerdictPath = Join-Path $warmupAttemptRoot 'verdict.json'
            $goldenComparisonPath = Join-Path $warmupAttemptRoot 'golden-baseline-comparison.json'
            foreach ($requiredPath in @(
                    $cleanupReceiptPath, $warmupVerdictPath, $goldenComparisonPath)) {
                if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
                    throw "Warmup-only completion evidence is missing: $requiredPath"
                }
            }
            $cleanupReceipt = Get-Content -Raw -LiteralPath $cleanupReceiptPath | ConvertFrom-Json
            if ([long]$cleanupReceipt.deletedOrderCount -ne $expectedWarmupOrders -or
                    [long]$cleanupReceipt.deletedCallbackCount -ne $expectedWarmupOrders -or
                    [long]$cleanupReceipt.resetQuotaCount -ne $expectedWarmupOrders -or
                    [long]$cleanupReceipt.currentGroupOrderCount -ne 0L -or
                    [long]$cleanupReceipt.currentGroupCallbackCount -ne 0L -or
                    [long]$cleanupReceipt.retainedFormalOrderCount -ne 0L -or
                    [long]$cleanupReceipt.retainedFormalCallbackCount -ne 0L) {
                throw 'Warmup-only completion cleanup receipt is not the exact empty boundary.'
            }
            $warmupVerdict = Get-Content -Raw -LiteralPath $warmupVerdictPath | ConvertFrom-Json
            $goldenComparison = Get-Content -Raw -LiteralPath $goldenComparisonPath | ConvertFrom-Json
            [ordered]@{
                verdict = 'PASS'
                runId = $RunId
                groupCode = $group
                completedWarmupAttempt = $warmupResult.attempt
                warmupAttemptSequence = @(1, 2)
                formalExecuted = $false
                cleanupCompletedAt = $warmupResult.cleanupCompletedAt
                cleanupReceiptPath = $cleanupReceiptPath
                warmupVerdict = $warmupVerdict
                goldenComparison = $goldenComparison
                completedAt = [datetimeoffset]::UtcNow.ToString('O')
            } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (
                Join-Path $outputRoot 'warmup-only-completion.json') -Encoding UTF8
            Save-State 'PASS' 'WARMUP_ONLY_COMPLETE'
            return
        }

        Save-State 'RUNNING' $group
        $script:currentGroupCode = $group
        $script:currentWarmupAttempt = $null
        $script:currentOriginStage = 'FORMAL_WAVE'
        $formalFirstRequestDeadline = $warmupResult.cleanupCompletedAt.AddSeconds(10)
        if ([datetimeoffset]::UtcNow -gt $formalFirstRequestDeadline) {
            throw "FORMAL_START_DEADLINE_EXPIRED: $group exceeded ten seconds before formal launch."
        }
        $segmentStartedAt = [datetimeoffset]::UtcNow
        & (Join-Path $PSScriptRoot 'Invoke-MembershipMillisecondBoundaryWave.ps1') `
            -GroupCode $group -RunId $RunId -OutputRoot $outputRoot `
            -HttpEvidenceRunId $TimingLogRunId `
            -RunScale $RunScale -ExecutionPhase 'FORMAL' -WarmupAttempt 0 `
            -FormalFirstRequestDeadlineEpochMillis $formalFirstRequestDeadline.ToUnixTimeMilliseconds() `
            -SourceFingerprint $sourceFingerprint -HostName $HostName -Port $Port -Protocol $Protocol `
            -CreationConcurrency $CreationConcurrency -HttpConcurrency $HttpConcurrency `
            -PaymentConcurrency $PaymentConcurrency
        if ($LASTEXITCODE -ne 0) { throw "Boundary group failed: $group" }
        Assert-PostgresIdentityUnchanged
        Save-ApplicationPerformanceSnapshot -Name "formal-$group"
        $segmentApplication = Get-Content -Raw -LiteralPath (
            Join-Path $outputRoot "application-performance-formal-$group.json") |
            ConvertFrom-Json
        $baselineApplication = Get-Content -Raw -LiteralPath (
            Join-Path $outputRoot 'application-performance-baseline.json') |
            ConvertFrom-Json
        if ([double]$segmentApplication.redisWriteRejectedCount -gt
                [double]$baselineApplication.redisWriteRejectedCount) {
            throw "RELIABILITY_FAILURE: Redis write bulkhead rejection or timeout occurred in formal segment $group."
        }
        # 功能或可靠性错误已由 Wave 抛出；纯性能失败记录后继续其余区段。
        $segmentOutput = Join-Path $outputRoot $group
        $script:currentOriginStage = 'FORMAL_HTTP_REPORT'
        & (Join-Path $PSScriptRoot 'New-MembershipOrderCreateHttpReport.ps1') `
            -LogPath $httpTimingLogPath `
            -RunId $RunId `
            -HttpLogRunId $TimingLogRunId `
            -ScenarioOrdersCsvPath (Join-Path $segmentOutput 'scenario-orders.csv') `
            -RequestResultsCsvPath (Join-Path $segmentOutput 'request-results.csv') `
            -OutputDirectory $segmentOutput `
            -MinimumQps 900 `
            -MaximumWallClockSeconds $maximumFormalWallClockSeconds `
            -MinimumEffectiveConcurrency 200 `
            -RequirePaymentOverlap `
            -AllowEventsOutsideManifest
        $segmentHttpVerdict = Get-Content -LiteralPath (
            Join-Path $segmentOutput 'order-create-http-verdict.json') -Raw | ConvertFrom-Json
        $segmentHttpFacts = @($segmentHttpVerdict.segments)
        if ($segmentHttpFacts.Count -ne 1 -or
                $null -eq $segmentHttpFacts[0].firstReceivedAtEpochMicros) {
            throw "Formal HTTP evidence does not contain one exact segment: $group"
        }
        $cleanupCompletedAt = [datetimeoffset]$warmupResult.cleanupCompletedAt
        $firstFormalReceivedAt = [datetimeoffset]::FromUnixTimeMilliseconds(
            [long]([Math]::Floor([long]$segmentHttpFacts[0].firstReceivedAtEpochMicros / 1000D)))
        $warmupToFormalGapSeconds = [Math]::Round(
            ($firstFormalReceivedAt - $cleanupCompletedAt).TotalSeconds, 6)
        $segmentHeartbeatPath = Join-Path $outputRoot 'heartbeat.json'
        $segmentProcessEvidence = if (Test-Path -LiteralPath $segmentHeartbeatPath -PathType Leaf) {
            Read-JsonSnapshot -Path $segmentHeartbeatPath
        } else {
            [pscustomobject]@{ applicationPid = $null; samplerPid = $null }
        }
        [ordered]@{
            groupCode = $group
            warmupAttempt = $warmupResult.attempt
            warmupRunId = $warmupResult.warmupRunId
            cleanupCompletedAt = $cleanupCompletedAt.ToString('O')
            firstFormalReceivedAtEpochMicros = [long]$segmentHttpFacts[0].firstReceivedAtEpochMicros
            gapSeconds = $warmupToFormalGapSeconds
            maximumGapSeconds = 10
            applicationPid = $segmentProcessEvidence.applicationPid
            postgresPid = $script:postgresStabilityBaseline.listenerPid
            samplerPid = $segmentProcessEvidence.samplerPid
            suitePid = $PID
            verdict = if ($warmupToFormalGapSeconds -le 10D -and
                    $warmupToFormalGapSeconds -ge 0D) { 'PASS' } else { 'FAIL' }
        } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (
            Join-Path $segmentOutput 'warmup-to-formal-gap.json') -Encoding UTF8
        if ($warmupToFormalGapSeconds -lt 0D -or $warmupToFormalGapSeconds -gt 10D) {
            throw "Warmup cleanup to formal request gap escaped ten seconds: $group/$warmupToFormalGapSeconds"
        }
        if ($segmentHttpVerdict.verdict -ne 'PASS') {
            $facts = @($segmentHttpVerdict.segments | ForEach-Object {
                "$($_.segment):$($_.wallClockSeconds)s/$($_.qps)QPS/" +
                    "$($_.effectiveCreateConcurrency) effective"
            }) -join ', '
            $segmentPerformanceFailures.Add(
                "Formal segment 900 QPS / concurrency 200 gate failed: $facts")
        }
        $null = & (Join-Path $PSScriptRoot 'New-MembershipPaymentFocusedTimingReport.ps1') `
            -LogPath $timingLogPath `
            -RunId $RunId `
            -LogRunId $TimingLogRunId `
            -ScenarioOrdersCsvPath (Join-Path $segmentOutput 'scenario-orders.csv') `
            -OutputDirectory $segmentOutput `
            -TopSlowCount 100 `
            -MinimumCompletedAtEpochMs $segmentStartedAt.ToUnixTimeMilliseconds()
        $script:currentOriginStage = 'FORMAL_GOLDEN_REPORT'
        $null = & (Join-Path $PSScriptRoot `
            'New-MembershipOrderCreateGoldenBaselineComparison.ps1') `
            -RunId $RunId `
            -HttpLogRunId $TimingLogRunId `
            -GroupCode $group `
            -ExecutionPhase 'FORMAL' `
            -ScenarioOrdersCsvPath (Join-Path $segmentOutput 'scenario-orders.csv') `
            -HttpEventsLogPath $httpTimingLogPath `
            -FocusedSummaryCsvPath (Join-Path $segmentOutput `
                'membership-payment-focused-operation-summary.csv') `
            -GoldenBaselineRunId $GoldenBaselineRunId `
            -GoldenBaselineEvidenceRoot $GoldenBaselineEvidenceRoot `
            -OutputDirectory $segmentOutput `
            -AllowEventsOutsideScenarioManifest
        if ($groupIndex -lt $groups.Count - 1) {
            Save-State 'STABILIZING' $group
            Wait-InterSegmentStability `
                -CompletedGroup $group `
                -ScenarioOrdersCsvPath (Join-Path $segmentOutput 'scenario-orders.csv') `
                -OutputDirectory $segmentOutput `
                -QuietSeconds $InterSegmentSeconds
        }
    }

    Save-State 'VERIFYING' 'FINAL'
    if ((Get-SourceFingerprint) -ne $sourceFingerprint) {
        throw 'Source fingerprint changed before final verification.'
    }
    $allScenarioOrders = Join-Path $outputRoot 'scenario-orders-all.csv'
    $allCallbackDispatch = Join-Path $outputRoot 'callback-dispatch-all.csv'
    $allRequestResults = Join-Path $outputRoot 'request-results-all.csv'
    $finalTimestampEvidence = Join-Path $outputRoot 'final-timestamp-evidence.csv'
    $scenarioPaths = @($groups | ForEach-Object {
        Join-Path $outputRoot "$_\scenario-orders.csv"
    })
    $dispatchPaths = @($groups | ForEach-Object {
        Join-Path $outputRoot "$_\callback-dispatch.csv"
    })
    $requestResultPaths = @($groups | ForEach-Object {
        Join-Path $outputRoot "$_\request-results.csv"
    })
    [void](Merge-CsvFiles -Paths $scenarioPaths -Destination $allScenarioOrders `
        -ExpectedRows $expectedRunOrders)
    [void](Merge-CsvFiles -Paths $dispatchPaths -Destination $allCallbackDispatch `
        -ExpectedRows $expectedRunOrders)
    [void](Merge-CsvFiles -Paths $requestResultPaths -Destination $allRequestResults `
        -ExpectedRows ($expectedRunOrders * 3 + $groups.Count * 25))

    # 每个正式 Wave 已用同一份区段清单完成 PostgreSQL、回调、权益和毫秒边界裁决；
    # 聚合层只合并这些已通过的服务端事实，避免依赖另一份会重复解释业务规则的总表 SQL。
    $serverVerdictPaths = @($groups | ForEach-Object {
        Join-Path $outputRoot "$_\server-time-verdict.csv"
    })
    [void](Merge-CsvFiles -Paths $serverVerdictPaths -Destination $finalTimestampEvidence `
        -ExpectedRows $expectedRunOrders)
    foreach ($verifiedGroup in $groups) {
        $waveVerdictPath = Join-Path $outputRoot "$verifiedGroup\verdict.json"
        $waveVerdict = Get-Content -LiteralPath $waveVerdictPath -Raw | ConvertFrom-Json
        if ($waveVerdict.verdict -ne 'PASS') {
            throw "$verifiedGroup wave did not pass its functional consistency gate."
        }
    }
    [ordered]@{
        verdict = 'PASS'
        sources = @($groups | ForEach-Object { "$_/verdict.json" })
        timestampEvidenceSources = @($groups | ForEach-Object { "$_/server-time-verdict.csv" })
        expectedOrders = $expectedRunOrders
        actualTimestampEvidenceRows = Get-CsvDataRowCount $finalTimestampEvidence
        resumedFrom = if ($SkipInitialGates) { $StartGroupCode } else { $null }
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (
        Join-Path $outputRoot 'wave-functional-verification.json') -Encoding UTF8
    Save-RedisPerformanceSnapshot -Name 'final'
    Save-ApplicationPerformanceSnapshot -Name 'final'

    Save-State 'REPORTING' 'TIMING'
    Wait-TimingLogQuiescence -Path $timingLogPath -ExpectedRunId $TimingLogRunId
    $timingReportArguments = @{
        LogPath = $timingLogPath
        RunId = $RunId
        LogRunId = $TimingLogRunId
        ScenarioOrdersCsvPath = $allScenarioOrders
        OutputDirectory = $timingOutputDirectory
        TopSlowCount = 100
        MinimumCompletedAtEpochMs = $formalStartedAt.ToUnixTimeMilliseconds()
        AllowEventsOutsideScenarioManifest = $true
    }
    & (Join-Path $PSScriptRoot `
        'New-MembershipPaymentFocusedTimingReport.ps1') @timingReportArguments

    $timingDetailsPath = Join-Path $timingOutputDirectory `
        'membership-payment-focused-events.csv'
    $timingSummaryPath = Join-Path $timingOutputDirectory `
        'membership-payment-focused-operation-summary.csv'
    $timingTopPath = Join-Path $timingOutputDirectory `
        'membership-payment-focused-top-100.csv'
    $timingDiagnosticsPath = Join-Path $timingOutputDirectory `
        'membership-payment-slow-failure-diagnostics.csv'
    $timingJsonPath = Join-Path $timingOutputDirectory `
        'membership-payment-focused-report.json'
    $timingMarkdownPath = Join-Path $timingOutputDirectory `
        'membership-payment-focused-report.md'
    foreach ($path in @(
        $timingDetailsPath,
        $timingSummaryPath,
        $timingTopPath,
        $timingDiagnosticsPath,
        $timingJsonPath,
        $timingMarkdownPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Focused timing report artifact is missing: $path"
        }
    }
    $timingReport = Get-Content -LiteralPath $timingJsonPath -Raw | ConvertFrom-Json
    if ($timingReport.runId -ne $RunId -or [long]$timingReport.eventCount -le 0L) {
        throw 'Focused timing JSON does not belong to this run or is empty.'
    }
    if ((Get-CsvDataRowCount $timingDetailsPath) -ne [long]$timingReport.eventCount) {
        throw 'Focused timing event count differs from the parsed log count.'
    }
    if ((Get-CsvDataRowCount $timingDiagnosticsPath) -ne
            [long]$timingReport.diagnosticEventCount) {
        throw 'Slow or failed diagnostic event count differs from the parsed log count.'
    }
    $timingOperations = @(Import-Csv -LiteralPath $timingSummaryPath)
    $invalidTimingOperations = @($timingOperations | Where-Object {
        ([long]$_.successCount + [long]$_.ackedCount + [long]$_.nackedCount +
            [long]$_.failedCount) -ne
            [long]$_.attemptCount
    })
    $expectedTimingOperations = @('ORDER_CREATE', 'PAYMENT_ATTEMPT')
    $actualTimingOperationSet = @($timingOperations.operation | Sort-Object) -join '|'
    $expectedTimingOperationSet = @($expectedTimingOperations | Sort-Object) -join '|'
    if ($timingOperations.Count -ne 2 -or $invalidTimingOperations.Count -ne 0 -or
            $actualTimingOperationSet -ne $expectedTimingOperationSet) {
        throw 'Focused timing summary must contain only two HTTP operations with balanced outcomes.'
    }
    foreach ($operation in @('ORDER_CREATE', 'PAYMENT_ATTEMPT')) {
        $row = @($timingOperations | Where-Object operation -eq $operation)
        if ($row.Count -ne 1 -or [long]$row[0].uniqueOrderCount -ne $expectedRunOrders -or
                [long]$row[0].failedCount -ne 0L -or [long]$row[0].nackedCount -ne 0L) {
            throw "Focused timing operation does not cover $expectedRunOrders unique orders: $operation"
        }
    }

    # 新口径只接受正式清单中的唯一 Trace，并以服务端接收与 HTTP 201 完成时间裁决每个区段。
    & (Join-Path $PSScriptRoot 'New-MembershipOrderCreateHttpReport.ps1') `
        -LogPath $httpTimingLogPath `
        -RunId $RunId `
        -HttpLogRunId $TimingLogRunId `
        -ScenarioOrdersCsvPath $allScenarioOrders `
        -RequestResultsCsvPath $allRequestResults `
        -OutputDirectory $outputRoot `
        -MinimumQps 900 `
        -MaximumWallClockSeconds $maximumFormalWallClockSeconds `
        -MinimumEffectiveConcurrency 200 `
        -RequirePaymentOverlap `
        -AllowEventsOutsideManifest
    $httpVerdictPath = Join-Path $outputRoot 'order-create-http-verdict.json'
    $httpQpsPath = Join-Path $outputRoot 'order-create-segment-qps.csv'
    $httpLatencyPath = Join-Path $outputRoot 'order-create-segment-latency.json'
    $httpEventsPath = Join-Path $outputRoot 'order-create-http-events.log'
    $httpConcurrencyCurvePath = Join-Path $outputRoot `
        'order-create-payment-concurrency-curve.csv'
    foreach ($path in @(
            $httpVerdictPath,
            $httpQpsPath,
            $httpLatencyPath,
            $httpEventsPath,
            $httpConcurrencyCurvePath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Order-create HTTP evidence artifact is missing: $path"
        }
    }
    $httpVerdict = Get-Content -LiteralPath $httpVerdictPath -Raw | ConvertFrom-Json
    if ($httpVerdict.runId -ne $RunId -or
            [long]$httpVerdict.scenarioTraceCount -ne $expectedRunOrders) {
        throw "Order-create HTTP evidence does not cover this formal $expectedRunOrders-order run."
    }

    $performanceFailures = [Collections.Generic.List[string]]::new()
    foreach ($failure in $segmentPerformanceFailures) {
        $performanceFailures.Add($failure)
    }
    if ($httpVerdict.verdict -ne 'PASS') {
        $failedSegments = @($httpVerdict.segments | Where-Object verdict -ne 'PASS' |
            ForEach-Object {
                "$($_.segment):$($_.wallClockSeconds)s/$($_.qps)QPS/" +
                    "$($_.effectiveCreateConcurrency) effective/overlap=$($_.paymentOverlap)"
            })
        $performanceFailures.Add(
            "Order-create direct concurrency 200 / 900 QPS gate failed: $($failedSegments -join ', ')")
    }
    foreach ($row in $timingOperations) {
        if ([long]$row.atLeast1000MsCount -ne 0L) {
            $performanceFailures.Add("$($row.operation) contains totalMs >= 1000ms")
        }
        if ([double]$row.redisOrderWriteMaximumMs -ge 1000D) {
            $performanceFailures.Add("$($row.operation) redisOrderWriteMs maximum is >= 1000ms")
        }
        if ([double]$row.redisProviderWriteMaximumMs -ge 1000D) {
            $performanceFailures.Add("$($row.operation) redisProviderWriteMs maximum is >= 1000ms")
        }
    }
    if (-not $DirectConcurrencyCanary -and $RunScale -eq 'PERFORMANCE_40K' -and
            -not [bool]$timingReport.logVolumeTargetMet) {
        $performanceFailures.Add(
            "Timing log volume is $($timingReport.runLogMiB) MiB; target is 19-28 MiB")
    }
    $redisFinal = Get-Content -LiteralPath (
        Join-Path $outputRoot 'redis-performance-final.json') -Raw | ConvertFrom-Json
    if ([long]$redisFinal.slowlogLength -ne 0L) {
        $performanceFailures.Add(
            "Redis SLOWLOG contains $($redisFinal.slowlogLength) entries after the formal reset")
    }
    $applicationBaseline = Get-Content -LiteralPath (
        Join-Path $outputRoot 'application-performance-baseline.json') -Raw | ConvertFrom-Json
    $applicationFinal = Get-Content -LiteralPath (
        Join-Path $outputRoot 'application-performance-final.json') -Raw | ConvertFrom-Json
    if ([double]$applicationFinal.redisWriteRejectedCount -gt
            [double]$applicationBaseline.redisWriteRejectedCount) {
        throw 'RELIABILITY_FAILURE: Membership Redis write bulkhead recorded a rejection or timeout.'
    }
    $conclusion = if ($performanceFailures.Count -eq 0) {
        '功能与性能均 PASS。'
    } else {
        '功能 PASS，但性能目标未达到。'
    }
    [ordered]@{
        conclusion = $conclusion
        performancePassed = $performanceFailures.Count -eq 0
        failures = @($performanceFailures)
        runLogMiB = $timingReport.runLogMiB
        logVolumeTargetMet = $timingReport.logVolumeTargetMet
        redisSlowlogLength = $redisFinal.slowlogLength
        redisWriteRejectedDelta = [double]$applicationFinal.redisWriteRejectedCount -
            [double]$applicationBaseline.redisWriteRejectedCount
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (
        Join-Path $outputRoot 'performance-verdict.json') -Encoding UTF8

    foreach ($path in @(
            $timingLogPath,
            $timingDetailsPath,
            $timingSummaryPath,
            $timingTopPath,
            $timingDiagnosticsPath,
            $timingJsonPath,
            $timingMarkdownPath)) {
        Copy-EvidenceFileIfNeeded -Path $path -DestinationDirectory $outputRoot
    }

    if ((Get-SourceFingerprint) -ne $sourceFingerprint) {
        throw 'Source fingerprint changed before final verdict.'
    }

    # 第二轮结束后始终保留数据库、Redis、JTL、SLOWLOG、计时日志和报告，等待人工分析。
    [ordered]@{
        runId = $RunId
        runScale = $RunScale
        orders = $expectedRunOrders
        callbacks = $expectedRunOrders
        preservePolicy = 'ALWAYS'
        preservedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath (Join-Path $outputRoot 'data-preserved.json') -Encoding UTF8
    Get-ChildItem -LiteralPath $tokenRoot -Filter "$RunId-*.csv" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force

    [ordered]@{
        verdict = 'PASS'
        runId = $RunId
        gitHead = $gitHead
        sourceFingerprint = $sourceFingerprint
        jarSha256 = $jarSha256
        runScale = $RunScale
        actualOrders = $expectedRunOrders
        dataPreserved = $true
        timestampEvidence = 'final-timestamp-evidence.csv'
        warmupEvidence = 'each-group/warmup/attempt-N'
        formalStartedAtEpochMs = $formalStartedAt.ToUnixTimeMilliseconds()
        timingLog = 'logs/membership-payment-state-machine.log'
        timingReport = 'logs/membership-payment-focused-report.md'
        orderCreateHttpVerdict = 'order-create-http-verdict.json'
        orderCreateHttpQps = 'order-create-segment-qps.csv'
        orderCreateConcurrencyCurve = 'order-create-payment-concurrency-curve.csv'
        concurrency200Verdict = $httpVerdict.concurrency200Verdict
        conclusion = $conclusion
        performancePassed = $performanceFailures.Count -eq 0
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $suiteVerdictPath -Encoding UTF8
    Save-State 'PASS' 'COMPLETE'
} catch {
    # 失败时保留订单、Token、JTL、SQL 和全部中间证据，禁止把失败结果清理后伪装成 PASS。
    $failureRecord = $_
    $failureMessage = $failureRecord.Exception.Message
    $failureVerdict = if ($failureMessage -like 'TEST_INVALID_*' -or
            $failureMessage -like 'FORMAL_START_DEADLINE_EXPIRED:*' -or
            $failureMessage -match 'being used by another process|\u65e0\u6cd5\u521b\u5efa\u8be5\u6587\u4ef6' -or
            $failureMessage -match 'Source fingerprint changed|packaged JAR|configuration drift') {
        'TEST_INVALID'
    } elseif ($failureMessage -like 'PREHEAT_INSUFFICIENT:*') {
        'PERFORMANCE_FAIL'
    } else { 'FAIL' }
    if ($failureVerdict -ne 'TEST_INVALID' -and
            $null -ne $script:postgresStabilityBaseline) {
        try {
            Assert-PostgresIdentityUnchanged
        } catch {
            $postgresIdentityFailure = $_.Exception.Message
            $failureMessage = "$failureMessage; PostgreSQL identity verification also failed: $postgresIdentityFailure"
            $failureVerdict = 'TEST_INVALID'
        }
    }
    $failureCode = if ($failureRecord.Exception.Message -match
            '^(?<code>[A-Z][A-Z0-9_]+):') {
        $Matches.code
    } else { 'MILLISECOND_BOUNDARY_SUITE_FAILURE' }
    [ordered]@{
        verdict = $failureVerdict
        schemaVersion = 1
        failureClass = $failureVerdict
        failureCode = $failureCode
        primaryMessage = $failureRecord.Exception.Message
        originComponent = 'MILLISECOND_BOUNDARY_SUITE'
        originStage = $script:currentOriginStage
        originState = 'RUNNING'
        groupCode = $script:currentGroupCode
        warmupAttempt = $script:currentWarmupAttempt
        exceptionType = $failureRecord.Exception.GetType().FullName
        scriptName = $failureRecord.InvocationInfo.ScriptName
        scriptLineNumber = $failureRecord.InvocationInfo.ScriptLineNumber
        positionMessage = $failureRecord.InvocationInfo.PositionMessage
        scriptStackTrace = $failureRecord.ScriptStackTrace
        runId = $RunId
        sourceFingerprint = $sourceFingerprint
        conclusion = if ($failureVerdict -eq 'TEST_INVALID') {
            '测试无效：PostgreSQL 5431、固定 JAR、源码指纹、采样或证据发布环境发生变化。'
        } elseif ($failureVerdict -eq 'PERFORMANCE_FAIL') {
            '功能 PASS，但预热②完整区段未达到合同性能门槛。'
        } else {
            '功能、可靠性或数据一致性门禁失败。'
        }
        message = $failureMessage
        failedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $suiteVerdictPath -Encoding UTF8
    Save-State $failureVerdict 'STOPPED' $failureMessage
    throw
}
