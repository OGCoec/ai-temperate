[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,128}$')]
    [string] $RunId,
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
    [string] $RabbitManagementUrl = 'http://127.0.0.1:15673/api/queues/%2F'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$outputRoot = Join-Path $repositoryRoot `
    "loadtest-output\soak\$RunId\millisecond-boundary"
$statePath = Join-Path $outputRoot 'soak-state.json'
$stopPath = Join-Path $outputRoot 'evidence-sampler.stop'
$failurePath = Join-Path $outputRoot 'queue-runtime-sampler-failure.json'
$hikariBaselinePath = Join-Path $outputRoot 'hikari-metrics-baseline.json'
$hikariFinalPath = Join-Path $outputRoot 'hikari-metrics-final.json'
$queueInspectionUrl = "http://127.0.0.1:$Port" +
    '/internal/test/membership-payments/loadtest-inspection/queues'
$runtimeInspectionUrl = "http://127.0.0.1:$Port" +
    '/internal/test/membership-payments/loadtest-inspection/runtime'
$paymentQueueName = 'membership.payment.check.queue'
$closingQueueName = 'membership.closing.check.queue'

if (-not (Test-Path -LiteralPath $outputRoot -PathType Container)) {
    throw "Formal run directory is missing: $outputRoot"
}
if ($QueueIntervalMillis -gt $RuntimeIntervalMillis) {
    throw 'Queue sampling interval cannot exceed the runtime sampling interval.'
}
if ($HikariMinimumIdle -gt $HikariMaximumPoolSize) {
    throw 'Hikari minimum idle cannot exceed maximum pool size.'
}
$rabbitUri = [uri]$RabbitManagementUrl
if ($rabbitUri.Scheme -ne 'http' -or
        $rabbitUri.Host -notin @('127.0.0.1','localhost','::1')) {
    throw 'RabbitMQ management sampling must use loopback HTTP.'
}

Import-Module (Join-Path $PSScriptRoot `
    'MembershipSchedulerIndexHikariEvidence.psm1') -Force

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

function New-LoopbackHttpClient {
    $handler = [System.Net.Http.SocketsHttpHandler]::new()
    $handler.MaxConnectionsPerServer = 1
    $handler.PooledConnectionIdleTimeout = [timespan]::FromMinutes(5)
    $handler.PooledConnectionLifetime = [timespan]::FromHours(1)
    $client = [System.Net.Http.HttpClient]::new($handler, $true)
    $client.Timeout = [timespan]::FromSeconds(15)
    return $client
}

function Get-JsonResponse(
        [System.Net.Http.HttpClient] $Client,
        [string] $Uri) {
    $response = $Client.GetAsync($Uri).GetAwaiter().GetResult()
    try {
        [void]$response.EnsureSuccessStatusCode()
        $json = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return $json | ConvertFrom-Json
    } finally {
        $response.Dispose()
    }
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
        $writer = [IO.StreamWriter]::new(
            (Join-Path $outputRoot $Name),
            $false,
            [Text.UTF8Encoding]::new($false))
        $writer.WriteLine($lines[0])
        $script:csvWriters[$Name] = $writer
    }
    $script:csvWriters[$Name].WriteLine($lines[1])
    $script:csvWriters[$Name].Flush()
}

function New-RedisWriteRuntimeSample {
    param(
        [Parameter(Mandatory = $true)] [string] $SampledAt,
        [Parameter(Mandatory = $true)] [string] $Segment,
        [Parameter(Mandatory = $true)] [object] $RuntimeProbe
    )

    $queueDepths = @($RuntimeProbe.redisWrite.queueDepths)
    $fullRestoreQueueDepths = @($RuntimeProbe.redisWrite.fullRestoreQueueDepths)
    $paymentAttemptPatchQueueDepths = @(
        $RuntimeProbe.redisWrite.paymentAttemptPatchQueueDepths)
    if (-not [bool]$RuntimeProbe.redisWrite.accepting -or
            [int]$RuntimeProbe.redisWrite.configuredBatchSize -ne $RedisWriteBatchSize -or
            [int]$RuntimeProbe.redisWrite.configuredLaneCount -ne $RedisWriteLaneCount -or
            [int]$RuntimeProbe.redisWrite.maximumInflight -ne $RedisWriteMaximumInflight -or
            $queueDepths.Count -ne $RedisWriteLaneCount -or
            $fullRestoreQueueDepths.Count -ne $RedisWriteLaneCount -or
            $paymentAttemptPatchQueueDepths.Count -ne $RedisWriteLaneCount) {
        throw "Redis write runtime contract does not match batch=$RedisWriteBatchSize, lane=$RedisWriteLaneCount, inflight=$RedisWriteMaximumInflight."
    }
    for ($lane = 0; $lane -lt $RedisWriteLaneCount; $lane += 1) {
        if ([int]$queueDepths[$lane] -ne
                [int]$fullRestoreQueueDepths[$lane] +
                [int]$paymentAttemptPatchQueueDepths[$lane]) {
            throw "Redis write lane $lane total depth does not equal its two classified queues."
        }
    }
    $inflight = [int]$RuntimeProbe.redisWrite.inflight
    $availablePermits = [int]$RuntimeProbe.redisWrite.availablePermits
    if ($inflight -lt 0 -or $inflight -gt $RedisWriteMaximumInflight -or
            $availablePermits -lt 0 -or
            $availablePermits -gt $RedisWriteMaximumInflight -or
            $inflight + $availablePermits -ne $RedisWriteMaximumInflight) {
        throw "Redis write runtime capacity escaped its fixed $RedisWriteMaximumInflight-order bound."
    }
    return [pscustomobject][ordered]@{
        sampledAt = $SampledAt
        segment = $Segment
        accepting = [bool]$RuntimeProbe.redisWrite.accepting
        configuredBatchSize = [int]$RuntimeProbe.redisWrite.configuredBatchSize
        configuredLaneCount = [int]$RuntimeProbe.redisWrite.configuredLaneCount
        maximumInflight = [int]$RuntimeProbe.redisWrite.maximumInflight
        inflight = $inflight
        availablePermits = $availablePermits
        lane0QueueDepth = [int]$queueDepths[0]
        lane1QueueDepth = [int]$queueDepths[1]
        lane2QueueDepth = [int]$queueDepths[2]
        lane3QueueDepth = [int]$queueDepths[3]
        lane4QueueDepth = [int]$queueDepths[4]
        lane5QueueDepth = [int]$queueDepths[5]
        lane0FullRestoreQueueDepth = [int]$fullRestoreQueueDepths[0]
        lane1FullRestoreQueueDepth = [int]$fullRestoreQueueDepths[1]
        lane2FullRestoreQueueDepth = [int]$fullRestoreQueueDepths[2]
        lane3FullRestoreQueueDepth = [int]$fullRestoreQueueDepths[3]
        lane4FullRestoreQueueDepth = [int]$fullRestoreQueueDepths[4]
        lane5FullRestoreQueueDepth = [int]$fullRestoreQueueDepths[5]
        lane0PaymentAttemptPatchQueueDepth = [int]$paymentAttemptPatchQueueDepths[0]
        lane1PaymentAttemptPatchQueueDepth = [int]$paymentAttemptPatchQueueDepths[1]
        lane2PaymentAttemptPatchQueueDepth = [int]$paymentAttemptPatchQueueDepths[2]
        lane3PaymentAttemptPatchQueueDepth = [int]$paymentAttemptPatchQueueDepths[3]
        lane4PaymentAttemptPatchQueueDepth = [int]$paymentAttemptPatchQueueDepths[4]
        lane5PaymentAttemptPatchQueueDepth = [int]$paymentAttemptPatchQueueDepths[5]
    }
}

$applicationClient = New-LoopbackHttpClient
$rabbitClient = New-LoopbackHttpClient
$rabbitUser = Get-ContainerEnvironmentValue `
    -Container $RabbitContainer -Name 'RABBITMQ_DEFAULT_USER'
$rabbitPassword = Get-ContainerEnvironmentValue `
    -Container $RabbitContainer -Name 'RABBITMQ_DEFAULT_PASS'
$rabbitCredential = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes("$rabbitUser`:$rabbitPassword"))
$rabbitClient.DefaultRequestHeaders.Authorization =
    [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
        'Basic', $rabbitCredential)
$lastRuntimeAt = [datetimeoffset]::MinValue
$lastRuntimeProbe = $null
$rabbitSampleIntervalMillis = 5000
$lastRabbitSampleAt = [datetimeoffset]::MinValue
$lastRabbitQueues = $null
$exitCode = 0
try {
    while (-not (Test-Path -LiteralPath $stopPath)) {
        $iterationStarted = [diagnostics.stopwatch]::StartNew()
        $sampledAt = [datetimeoffset]::UtcNow.ToString('O')
        $segment = Get-MembershipEvidenceSegment -StatePath $statePath
        $redisQueues = Get-JsonResponse `
            -Client $applicationClient -Uri $queueInspectionUrl
        $now = [datetimeoffset]::UtcNow
        # Management API 是周期聚合观测面；每五秒刷新一次即可，循环间继续发布最近样本供诊断使用。
        if ($null -eq $lastRabbitQueues -or
                ($now - $lastRabbitSampleAt).TotalMilliseconds -ge
                    $rabbitSampleIntervalMillis) {
            $rabbitQueueResponse = Get-JsonResponse `
                -Client $rabbitClient -Uri $rabbitUri.AbsoluteUri
            # PowerShell 7 会把顶层 JSON 数组作为一个结果对象，赋值后再展开才能稳定获得队列条目。
            $lastRabbitQueues = @($rabbitQueueResponse)
            $lastRabbitSampleAt = $now
        }
        $queueRow = New-MembershipSchedulerQueueSample `
            -SampledAt $sampledAt `
            -Segment $segment `
            -RedisQueues $redisQueues `
            -RabbitQueues $lastRabbitQueues
        Write-EvidenceCsvRow -Name 'scheduler-queue-samples.csv' -Row $queueRow

        $now = [datetimeoffset]::UtcNow
        if (($now - $lastRuntimeAt).TotalMilliseconds -ge $RuntimeIntervalMillis) {
            $runtime = Get-JsonResponse `
                -Client $applicationClient -Uri $runtimeInspectionUrl
            if (-not [bool]$runtime.hikari.poolAvailable -or
                    [int]$runtime.hikari.configuredMaximumPoolSize -ne
                        $HikariMaximumPoolSize -or
                    [int]$runtime.hikari.configuredMinimumIdle -ne
                        $HikariMinimumIdle) {
                throw "Hikari runtime contract is unavailable or not configured as $HikariMaximumPoolSize/$HikariMinimumIdle."
            }
            if ([double]$runtime.hikari.timeoutCount -gt 0D) {
                throw 'Hikari connection timeout counter is non-zero.'
            }
            $lastRuntimeProbe = $runtime
            if (-not (Test-Path -LiteralPath $hikariBaselinePath)) {
                $runtime | ConvertTo-Json -Depth 12 |
                    Set-Content -LiteralPath $hikariBaselinePath -Encoding UTF8
            }
            $hikariRow = New-MembershipHikariSample `
                -SampledAt $sampledAt -Segment $segment -RuntimeProbe $runtime
            Write-EvidenceCsvRow -Name 'hikari-runtime-samples.csv' -Row $hikariRow
            $redisWriteRow = New-RedisWriteRuntimeSample `
                -SampledAt $sampledAt -Segment $segment -RuntimeProbe $runtime
            Write-EvidenceCsvRow `
                -Name 'redis-write-runtime-samples.csv' -Row $redisWriteRow
            $lastRuntimeAt = $now
        }
        $remainingMillis = $QueueIntervalMillis -
            [int][Math]::Ceiling($iterationStarted.Elapsed.TotalMilliseconds)
        if ($remainingMillis -gt 0) {
            Start-Sleep -Milliseconds $remainingMillis
        }
    }
} catch {
    $exitCode = 1
    [ordered]@{
        verdict = 'FAIL'
        failedAt = [datetimeoffset]::UtcNow.ToString('O')
        message = $_.Exception.Message
        exceptionType = $_.Exception.GetType().FullName
    } | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $failurePath -Encoding UTF8
} finally {
    foreach ($writer in @($script:csvWriters.Values)) {
        $writer.Dispose()
    }
    if ($null -ne $lastRuntimeProbe) {
        $lastRuntimeProbe | ConvertTo-Json -Depth 12 |
            Set-Content -LiteralPath $hikariFinalPath -Encoding UTF8
    }
    $applicationClient.Dispose()
    $rabbitClient.Dispose()
}

exit $exitCode
