[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,128}$')]
    [string] $RunId,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int] $AppPid,
    [ValidateRange(1, 65535)]
    [int] $Port = 6655,
    [ValidateRange(250, 500)]
    [int] $QueueIntervalMillis = 500,
    [ValidateRange(500, 1000)]
    [int] $RuntimeIntervalMillis = 1000,
    [ValidateRange(1, 1024)]
    [int] $HikariMaximumPoolSize = 96,
    [ValidateRange(0, 1024)]
    [int] $HikariMinimumIdle = 8,
    [ValidateSet(64)]
    [int] $RedisWriteBatchSize = 64,
    [ValidateSet(6)]
    [int] $RedisWriteLaneCount = 6,
    [ValidateSet(384)]
    [int] $RedisWriteMaximumInflight = 384,
    [string] $RabbitContainer = 'rabbitmq1',
    [string] $RabbitManagementUrl = 'http://127.0.0.1:15673/api/queues/%2F',
    [string] $PostgresUrl = '',
    [ValidateRange(32, 1024)]
    [int] $PostgresMaxConnections = 384,
    [ValidateRange(0, 64)]
    [int] $MaximumNavicatConnections = 8
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$outputRoot = Join-Path $repositoryRoot (
    "loadtest-output\soak\$RunId\millisecond-boundary")
$statePath = Join-Path $outputRoot 'soak-state.json'
$stopPath = Join-Path $outputRoot 'evidence-sampler.stop'
$failurePath = Join-Path $outputRoot 'evidence-sampler-failure.json'
$postgresSqlPath = Join-Path $outputRoot 'postgres-connection-watch.sql'
$postgresRawPath = Join-Path $outputRoot 'postgres-connection-samples.raw.txt'
$postgresCsvPath = Join-Path $outputRoot 'postgres-connection-samples.csv'
$postgresErrorPath = Join-Path $outputRoot 'postgres-connection-sampler.err.log'
$hikariBaselinePath = Join-Path $outputRoot 'hikari-metrics-baseline.json'
$hikariFinalPath = Join-Path $outputRoot 'hikari-metrics-final.json'
$baseUrl = "http://127.0.0.1:$Port"
$queueInspectionUrl = $baseUrl +
    '/internal/test/membership-payments/loadtest-inspection/queues'
$runtimeInspectionUrl = $baseUrl +
    '/internal/test/membership-payments/loadtest-inspection/runtime'
$paymentQueueName = 'membership.payment.check.queue'
$closingQueueName = 'membership.closing.check.queue'

if (-not (Test-Path -LiteralPath $outputRoot -PathType Container)) {
    throw "Formal run directory is missing: $outputRoot"
}
if (Test-Path -LiteralPath $stopPath) {
    throw "Evidence sampler stop file already exists: $stopPath"
}
if ($QueueIntervalMillis -gt $RuntimeIntervalMillis) {
    throw 'Queue sampling interval cannot exceed the runtime sampling interval.'
}
if ($HikariMinimumIdle -gt $HikariMaximumPoolSize) {
    throw 'Hikari minimum idle cannot exceed maximum pool size.'
}
$rabbitUri = [uri]$RabbitManagementUrl
if ($rabbitUri.Scheme -ne 'http' -or
        $rabbitUri.Host -notin @('127.0.0.1', 'localhost', '::1')) {
    throw 'RabbitMQ management sampling must use loopback HTTP.'
}

Import-Module (
    Join-Path $PSScriptRoot 'MembershipSchedulerIndexHikariEvidence.psm1') -Force

function Get-ContainerEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)] [string] $Container,
        [Parameter(Mandatory = $true)] [string] $Name
    )

    $raw = @(& docker inspect $Container 2>$null)
    if ($LASTEXITCODE -ne 0 -or $raw.Count -eq 0) {
        throw "Container inspection failed: $Container"
    }
    $document = ($raw -join "`n") | ConvertFrom-Json
    $prefix = "$Name="
    $entry = @($document[0].Config.Env | Where-Object {
        [string]$_ -like "$prefix*"
    } | Select-Object -First 1)
    if ($entry.Count -ne 1) {
        throw "Container environment value is missing: $Container/$Name"
    }
    return ([string]$entry[0]).Substring($prefix.Length)
}

function New-RabbitAuthorizationHeader {
    $user = Get-ContainerEnvironmentValue `
        -Container $RabbitContainer `
        -Name 'RABBITMQ_DEFAULT_USER'
    $password = Get-ContainerEnvironmentValue `
        -Container $RabbitContainer `
        -Name 'RABBITMQ_DEFAULT_PASS'
    $bytes = [Text.Encoding]::UTF8.GetBytes("$user`:$password")
    return 'Basic ' + [Convert]::ToBase64String($bytes)
}

$script:csvWriters = @{}
function Write-EvidenceCsvRow {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [object] $Row
    )

    $lines = @($Row | ConvertTo-Csv -NoTypeInformation)
    if ($lines.Count -ne 2) {
        throw "Evidence CSV conversion returned an invalid row: $Name"
    }
    if (-not $script:csvWriters.ContainsKey($Name)) {
        $path = Join-Path $outputRoot $Name
        $writer = [IO.StreamWriter]::new(
            $path,
            $false,
            [Text.UTF8Encoding]::new($false))
        $writer.WriteLine($lines[0])
        $script:csvWriters[$Name] = $writer
    }
    $script:csvWriters[$Name].WriteLine($lines[1])
    # 正式测试异常时必须保留最后一个已完成样本，因此每行立即刷盘。
    $script:csvWriters[$Name].Flush()
}

function New-HostRuntimeSample {
    param(
        [Parameter(Mandatory = $true)] [string] $SampledAt,
        [Parameter(Mandatory = $true)] [string] $Segment
    )

    $process = Get-Process -Id $AppPid -ErrorAction Stop
    $processor = Get-CimInstance `
        -ClassName Win32_PerfFormattedData_PerfOS_Processor `
        -Filter "Name='_Total'"
    $system = Get-CimInstance `
        -ClassName Win32_PerfFormattedData_PerfOS_System
    return [pscustomobject][ordered]@{
        sampledAt = $SampledAt
        segment = $Segment
        appCpuTotalSeconds = [double]$process.CPU
        appWorkingSetBytes = [long]$process.WorkingSet64
        appThreadCount = [int]$process.Threads.Count
        systemCpuPercent = [double]$processor.PercentProcessorTime
        contextSwitchesPerSecond = [long]$system.ContextSwitchesPersec
    }
}

function Get-LatestPostgresConnectionFacts {
    if (-not (Test-Path -LiteralPath $postgresRawPath -PathType Leaf)) {
        return $null
    }
    $lines = @(Get-Content -LiteralPath $postgresRawPath -Tail 32)
    for ($index = $lines.Count - 1; $index -ge 0; $index -= 1) {
        $line = [string]$lines[$index]
        if ([string]$line -match
                '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z,(\d+),\d+,\d+,(\d+),(\d+),(\d+),(\d+)$') {
            return [pscustomobject]@{
                totalConnections = [int]$Matches[1]
                navicatTotal = [int]$Matches[2]
                navicatActive = [int]$Matches[3]
                navicatIdleInTransaction = [int]$Matches[4]
                navicatWriteOrDdl = [int]$Matches[5]
            }
        }
    }
    return $null
}

$resolvedPostgresUrl = if (-not [string]::IsNullOrWhiteSpace($PostgresUrl)) {
    $PostgresUrl
} elseif (-not [string]::IsNullOrWhiteSpace(
        [Environment]::GetEnvironmentVariable('MEMBERSHIP_PAYMENT_POSTGRES_URL'))) {
    [Environment]::GetEnvironmentVariable('MEMBERSHIP_PAYMENT_POSTGRES_URL')
} elseif (-not [string]::IsNullOrWhiteSpace($env:POSTGRES_URL)) {
    $env:POSTGRES_URL
} else {
    'postgresql://postgres@127.0.0.1:5431/ai_temperate'
}
$psqlCommand = Get-Command psql -ErrorAction Stop
New-MembershipPostgresWatchScript |
    Set-Content -LiteralPath $postgresSqlPath -Encoding UTF8
$postgresProcess = Start-Process `
    -FilePath $psqlCommand.Source `
    -ArgumentList @(
        '-X', '-w', $resolvedPostgresUrl,
        '-v', 'ON_ERROR_STOP=1', '-q', '-A', '-t',
        '-f', $postgresSqlPath) `
    -RedirectStandardOutput $postgresRawPath `
    -RedirectStandardError $postgresErrorPath `
    -WindowStyle Hidden `
    -PassThru

$queueRuntimeFailurePath = Join-Path $outputRoot `
    'queue-runtime-sampler-failure.json'
$queueRuntimeStdoutPath = Join-Path $outputRoot `
    'queue-runtime-sampler.stdout.log'
$queueRuntimeStderrPath = Join-Path $outputRoot `
    'queue-runtime-sampler.stderr.log'
$queueRuntimeScript = Join-Path $PSScriptRoot `
    'Measure-MembershipPaymentQueueRuntimeEvidence.ps1'
$queueRuntimeProcess = Start-Process `
    -FilePath (Get-Command pwsh -ErrorAction Stop).Source `
    -ArgumentList @(
        '-NoProfile','-File',$queueRuntimeScript,
        '-RunId',$RunId,
        '-Port',[string]$Port,
        '-QueueIntervalMillis',[string]$QueueIntervalMillis,
        '-RuntimeIntervalMillis',[string]$RuntimeIntervalMillis,
        '-HikariMaximumPoolSize',[string]$HikariMaximumPoolSize,
        '-HikariMinimumIdle',[string]$HikariMinimumIdle,
        '-RedisWriteBatchSize',[string]$RedisWriteBatchSize,
        '-RedisWriteLaneCount',[string]$RedisWriteLaneCount,
        '-RedisWriteMaximumInflight',[string]$RedisWriteMaximumInflight,
        '-RabbitContainer',$RabbitContainer,
        '-RabbitManagementUrl',$RabbitManagementUrl) `
    -RedirectStandardOutput $queueRuntimeStdoutPath `
    -RedirectStandardError $queueRuntimeStderrPath `
    -WorkingDirectory $repositoryRoot `
    -WindowStyle Hidden `
    -PassThru
$exitCode = 0
try {
    while (-not (Test-Path -LiteralPath $stopPath)) {
        if ($postgresProcess.HasExited) {
            throw "PostgreSQL connection sampler exited early: $($postgresProcess.ExitCode)"
        }
        if ($queueRuntimeProcess.HasExited) {
            $queueMessage = if (Test-Path -LiteralPath $queueRuntimeFailurePath) {
                [string](Get-Content -Raw -LiteralPath $queueRuntimeFailurePath |
                    ConvertFrom-Json).message
            } else {
                "exit code $($queueRuntimeProcess.ExitCode)"
            }
            throw "Queue/runtime sampler exited early: $queueMessage"
        }
        if ($null -eq (Get-Process -Id $AppPid -ErrorAction SilentlyContinue)) {
            throw "Application process exited during evidence sampling: $AppPid"
        }
        $sampledAt = [datetimeoffset]::UtcNow.ToString('O')
        $segment = Get-MembershipEvidenceSegment -StatePath $statePath
        Write-EvidenceCsvRow `
            -Name 'host-runtime-samples.csv' `
            -Row (New-HostRuntimeSample -SampledAt $sampledAt -Segment $segment)
        $latestPostgres = Get-LatestPostgresConnectionFacts
        if ($null -ne $latestPostgres -and
                $latestPostgres.totalConnections -ge $PostgresMaxConnections) {
            throw "PostgreSQL total connections reached the hard limit: $($latestPostgres.totalConnections)"
        }
        if ($null -ne $latestPostgres -and
                $latestPostgres.navicatTotal -gt $MaximumNavicatConnections) {
            throw "Navicat observer connections exceeded the declared budget: $($latestPostgres.navicatTotal)"
        }
        if ($null -ne $latestPostgres -and
                $latestPostgres.navicatIdleInTransaction -gt 0) {
            throw 'Navicat observer contains an idle transaction.'
        }
        if ($null -ne $latestPostgres -and
                $latestPostgres.navicatWriteOrDdl -gt 0) {
            throw 'Navicat observer executed a write or DDL statement.'
        }
        Start-Sleep -Milliseconds $RuntimeIntervalMillis
    }
} catch {
    $exitCode = 1
    [ordered]@{
        verdict = 'FAIL'
        failedAt = [datetimeoffset]::UtcNow.ToString('O')
        message = $_.Exception.Message
        exceptionType = $_.Exception.GetType().FullName
    } | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath $failurePath -Encoding UTF8
} finally {
    if (-not $queueRuntimeProcess.HasExited) {
        if (-not (Test-Path -LiteralPath $stopPath)) {
            'parent-stop' | Set-Content -LiteralPath $stopPath -Encoding ascii
        }
        if (-not $queueRuntimeProcess.WaitForExit(20000)) {
            Stop-Process -Id $queueRuntimeProcess.Id -Force `
                -ErrorAction SilentlyContinue
            [void]$queueRuntimeProcess.WaitForExit(5000)
        }
    }
    if ($queueRuntimeProcess.HasExited -and
            $queueRuntimeProcess.ExitCode -ne 0 -and $exitCode -eq 0) {
        $exitCode = 1
        [ordered]@{
            verdict = 'FAIL'
            failedAt = [datetimeoffset]::UtcNow.ToString('O')
            message = 'Queue/runtime sampler failed during shutdown.'
            exceptionType = 'QueueRuntimeSamplerFailure'
        } | ConvertTo-Json -Depth 5 |
            Set-Content -LiteralPath $failurePath -Encoding UTF8
    }
    if ($null -ne $postgresProcess -and -not $postgresProcess.HasExited) {
        Stop-Process -Id $postgresProcess.Id -Force -ErrorAction SilentlyContinue
        [void]$postgresProcess.WaitForExit(5000)
    }
    foreach ($writer in @($script:csvWriters.Values)) {
        $writer.Dispose()
    }
    try {
        [void](Convert-MembershipPostgresWatchOutput `
            -InputPath $postgresRawPath `
            -OutputPath $postgresCsvPath)
    } catch {
        if ($exitCode -eq 0) {
            $exitCode = 1
            [ordered]@{
                verdict = 'FAIL'
                failedAt = [datetimeoffset]::UtcNow.ToString('O')
                message = $_.Exception.Message
                exceptionType = $_.Exception.GetType().FullName
            } | ConvertTo-Json -Depth 5 |
                Set-Content -LiteralPath $failurePath -Encoding UTF8
        }
    }
}

exit $exitCode
