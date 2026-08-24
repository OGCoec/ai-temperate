[CmdletBinding()]
param(
    [string] $RunId = '',
    [string] $HostName = '127.0.0.1',
    [int] $Port = 6655,
    [string] $Protocol = 'http',
    [ValidateRange(1, 4096)]
    [int] $CreationConcurrency = 4096,
    [ValidateRange(1, 4096)]
    [int] $HttpConcurrency = 4096,
    [ValidateRange(60, 600)]
    [int] $PrecheckSeconds = 120,
    [ValidateRange(60, 600)]
    [int] $InterSegmentSeconds = 60,
    [switch] $PreserveDataAfterPass
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'MembershipBoundaryRedis.ps1')
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$baseUrl = "$Protocol`://$HostName`:$Port"
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = 'membership-millisecond-boundary-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
}
$outputRoot = Join-Path $repositoryRoot "loadtest-output\soak\$RunId\millisecond-boundary"
$statePath = Join-Path $outputRoot 'soak-state.json'
$suiteVerdictPath = Join-Path $outputRoot 'verdict.json'
$tokenRoot = Join-Path $repositoryRoot 'loadtest\local\millisecond-boundary'
$forbiddenApplicationPort = [int]('80' + '80')

function Get-TextSha256([string] $Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Get-SourceFingerprint {
    Push-Location $repositoryRoot
    try {
        $paths = @(
            'ai-temperate-common', 'ai-temperate-model', 'ai-temperate-mapper',
            'ai-temperate-service', 'ai-temperate-web', 'loadtest', 'sql', 'docs', 'pom.xml')
        $head = (& git rev-parse HEAD).Trim()
        $diff = (& git diff --binary HEAD -- @paths | Out-String)
        $untracked = @(& git ls-files --others --exclude-standard -- @paths |
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
      WHERE id BETWEEN 70000000000000000 AND 70000000000039999),
    (SELECT COUNT(*) FROM user_profile
      WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000039999),
    COUNT(*),
    COUNT(*) FILTER (WHERE membership_tier = 0),
    COUNT(*) FILTER (WHERE quota_period_started_at IS NULL),
    COUNT(*) FILTER (WHERE membership_expires_at IS NULL),
    (SELECT COUNT(*) FROM membership_order
      WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000039999),
    (SELECT COUNT(*) FROM membership_order
      WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000039999
        AND status IN (0, 1)),
    (SELECT COUNT(*) FROM membership_payment_callback AS callback
      JOIN membership_order AS payment_order ON payment_order.id = callback.order_id
      WHERE payment_order.login_identity_id BETWEEN 70000000000000000 AND 70000000000039999)
FROM user_membership_quota
WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000039999;
'@
    $raw = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 -A -t -F '|' -c $sql)
    if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1) {
        throw 'PostgreSQL FREE baseline inspection failed.'
    }
    $actual = @($raw[0].Trim().Split('|') | ForEach-Object { [long]$_ })
    $expected = @(40000L, 40000L, 40000L, 40000L, 40000L, 40000L, 0L, 0L, 0L)
    if (($actual -join '|') -ne ($expected -join '|')) {
        throw "PostgreSQL FREE baseline is invalid: $($actual -join '|')"
    }
}

function Assert-RedisBoundaryBaseline {
    $container = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
    foreach ($pattern in @('ait:*:payment:*:v1:*', 'ait:*:payment:*:v2:*')) {
        $keys = @(Invoke-MembershipBoundaryRedisCli -Container $container `
            -Arguments @('--scan', '--pattern', $pattern))
        if ($keys.Count -ne 0) {
            throw "Redis boundary namespace is not empty: $pattern count=$($keys.Count)"
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
            [int]$_.channel_max -lt $CreationConcurrency
        }).Count -ne 0) {
        throw "RabbitMQ negotiated channel_max is below $CreationConcurrency."
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

function Wait-InterSegmentStability([string] $CompletedGroup, [int] $Seconds) {
    $deadline = [datetimeoffset]::UtcNow.AddSeconds($Seconds)
    do {
        if ((Get-SourceFingerprint) -ne $sourceFingerprint) {
            throw "Source fingerprint changed after group $CompletedGroup."
        }
        Assert-PortBoundary
        Assert-RabbitBoundaryBaseline
        $queues = Invoke-RestMethod -Method Get `
            -Uri "$baseUrl/internal/test/membership-payments/loadtest-inspection/queues" `
            -TimeoutSec 15
        foreach ($property in @(
            'callbackReadySize', 'callbackProcessingSize', 'dirtySize', 'dirtyProcessingSize')) {
            if ([long]$queues.$property -ne 0L) {
                throw "Redis queue changed during the post-group stability window: $property=$($queues.$property)"
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
        $raw = @(& psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 -A -t -F '|' -c $sql)
        if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1 -or $raw[0].Trim() -ne '0|0') {
            throw "PostgreSQL is not stable after group ${CompletedGroup}: $($raw -join ';')"
        }
        $remaining = [Math]::Max(0, [Math]::Ceiling(($deadline - [datetimeoffset]::UtcNow).TotalSeconds))
        Write-Output "STABILITY_HEARTBEAT group=$CompletedGroup remainingSeconds=$remaining"
        if ([datetimeoffset]::UtcNow -lt $deadline) {
            Start-Sleep -Seconds 2
        }
    } while ([datetimeoffset]::UtcNow -lt $deadline)
}

function Save-State([string] $State, [string] $Wave, [string] $Message = '') {
    [ordered]@{
        phase = 'MILLISECOND_BOUNDARY'
        state = $State
        wave = $Wave
        message = $Message
        runId = $RunId
        updatedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8
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
    [string] $Destination) {
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
    if ($count -ne 40000) {
        throw "Reset manifest contains $count order IDs; expected 40000."
    }
    return $count
}

if ($Port -ne 6655) { throw 'The suite is fixed to local port 6655.' }
foreach ($command in @('git', 'jmeter', 'psql', 'docker')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is unavailable: $command"
    }
}
New-Item -ItemType Directory -Force -Path $outputRoot, $tokenRoot | Out-Null
$sourceFingerprint = Get-SourceFingerprint
$startedAt = [datetimeoffset]::UtcNow
[ordered]@{
    runId = $RunId
    sourceFingerprint = $sourceFingerprint
    mode = 'loadtest-realtime'
    port = 6655
    precheck = "PT$($PrecheckSeconds)S"
    interSegment = "PT$($InterSegmentSeconds)S"
    pending = 'PT5M'
    closing = 'PT5M'
    users = 40000
    orders = 40000
    groups = @('E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR')
    formalStartedAt = $startedAt.ToString('O')
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputRoot 'run-manifest.json') -Encoding UTF8

try {
    Save-State 'PREPARING' 'FIXTURE'
    $prepared = Invoke-RestMethod -Method Post `
        -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/prepare" `
        -TimeoutSec 180
    if (-not $prepared.prepared -or $prepared.identityCount -ne 40000 `
        -or $prepared.profileCount -ne 40000 -or $prepared.quotaCount -ne 40000 `
        -or $prepared.orderCount -ne 0 -or $prepared.callbackCount -ne 0) {
        throw 'The persistent 40,000-user fixture is not at a clean FREE baseline.'
    }
    Assert-PortBoundary
    Assert-PostgresBoundaryBaseline
    Assert-RedisBoundaryBaseline
    Assert-RabbitBoundaryBaseline

    Save-State 'PRECHECK' 'PRECHECK'
    Start-Sleep -Seconds $PrecheckSeconds
    if ((Get-SourceFingerprint) -ne $sourceFingerprint) {
        throw 'Source fingerprint changed during the fixed precheck stability window.'
    }
    Assert-PortBoundary
    Assert-PostgresBoundaryBaseline
    Assert-RedisBoundaryBaseline
    Assert-RabbitBoundaryBaseline

    $groups = @('E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR')
    for ($groupIndex = 0; $groupIndex -lt $groups.Count; $groupIndex += 1) {
        $group = $groups[$groupIndex]
        Save-State 'RUNNING' $group
        & (Join-Path $PSScriptRoot 'Invoke-MembershipMillisecondBoundaryWave.ps1') `
            -GroupCode $group -RunId $RunId -OutputRoot $outputRoot `
            -SourceFingerprint $sourceFingerprint -HostName $HostName -Port $Port -Protocol $Protocol `
            -CreationConcurrency $CreationConcurrency -HttpConcurrency $HttpConcurrency
        if ($LASTEXITCODE -ne 0) { throw "Boundary group failed: $group" }
        if ($groupIndex -lt $groups.Count - 1) {
            Save-State 'STABILIZING' $group
            Wait-InterSegmentStability -CompletedGroup $group -Seconds $InterSegmentSeconds
        }
    }

    Save-State 'VERIFYING' 'FINAL'
    if ((Get-SourceFingerprint) -ne $sourceFingerprint) {
        throw 'Source fingerprint changed before final verification.'
    }
    $allScenarioOrders = Join-Path $outputRoot 'scenario-orders-all.csv'
    $allCallbackDispatch = Join-Path $outputRoot 'callback-dispatch-all.csv'
    $finalTimestampEvidence = Join-Path $outputRoot 'final-timestamp-evidence.csv'
    $scenarioPaths = @($groups | ForEach-Object {
        Join-Path $outputRoot "$_\scenario-orders.csv"
    })
    $dispatchPaths = @($groups | ForEach-Object {
        Join-Path $outputRoot "$_\callback-dispatch.csv"
    })
    [void](Merge-CsvFiles -Paths $scenarioPaths -Destination $allScenarioOrders -ExpectedRows 40000)
    [void](Merge-CsvFiles -Paths $dispatchPaths -Destination $allCallbackDispatch -ExpectedRows 40000)

    $finalSqlTemplate = Get-Content -Raw -LiteralPath (
        Join-Path $repositoryRoot 'loadtest\sql\verify-membership-millisecond-boundary-final.sql')
    $scenarioSqlPath = (($allScenarioOrders -replace '\\', '/') -replace "'", "''")
    $dispatchSqlPath = (($allCallbackDispatch -replace '\\', '/') -replace "'", "''")
    $evidenceSqlPath = (($finalTimestampEvidence -replace '\\', '/') -replace "'", "''")
    $finalSqlText = $finalSqlTemplate.
        Replace('__ALL_SCENARIO_ORDERS_CSV__', $scenarioSqlPath).
        Replace('__ALL_CALLBACK_DISPATCH_CSV__', $dispatchSqlPath).
        Replace('__FINAL_TIMESTAMP_EVIDENCE_CSV__', $evidenceSqlPath)
    $finalRunSql = Join-Path $outputRoot 'verify-final-run.sql'
    $finalSqlText | Set-Content -LiteralPath $finalRunSql -Encoding UTF8
    $finalOutput = Join-Path $outputRoot 'final-postgres-verification.txt'
    & psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 `
        -f $finalRunSql |
        Tee-Object -FilePath $finalOutput
    if ($LASTEXITCODE -ne 0 -or (Get-Content -Raw $finalOutput) -notmatch '\bPASS\b') {
        throw 'Final 40,000-order PostgreSQL verification failed.'
    }
    if (-not (Test-Path -LiteralPath $finalTimestampEvidence) `
        -or (Get-CsvDataRowCount $finalTimestampEvidence) -ne 40000) {
        throw 'Final timestamp evidence does not contain exactly 40,000 rows.'
    }

    if ((Get-SourceFingerprint) -ne $sourceFingerprint) {
        throw 'Source fingerprint changed before final verdict.'
    }

    if ($PreserveDataAfterPass) {
        # 最终验收通过后保留本轮数据库与 Redis 业务事实，便于逐笔复核微秒边界结果。
        [ordered]@{
            runId = $RunId
            orders = 40000
            callbacks = 40000
            preservedAt = [datetimeoffset]::UtcNow.ToString('O')
        } | ConvertTo-Json -Depth 4 |
            Set-Content -LiteralPath (Join-Path $outputRoot 'data-preserved.json') -Encoding UTF8
    } else {
        $resetManifest = Join-Path $outputRoot 'reset-order-ids.json'
        [void](Write-ResetOrderManifest -ScenarioPaths $scenarioPaths -Destination $resetManifest)
        $runOrderIds = @(
            foreach ($path in $scenarioPaths) {
                Import-Csv -LiteralPath $path | ForEach-Object { [string]$_.order_id }
            }
        )

        # 默认模式只删除本轮四万笔支付事实并恢复额度；固定账号、资料和额度模板永久保留。
        $reset = Invoke-RestMethod -Method Post `
            -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/reset" `
            -ContentType 'application/json' `
            -InFile $resetManifest `
            -TimeoutSec 300
        if (-not $reset.prepared -or $reset.orderCount -ne 0 -or $reset.callbackCount -ne 0) {
            throw 'Boundary reset did not preserve a clean persistent fixture.'
        }
        $redisContainer = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
        [void](Remove-MembershipBoundaryRedisOrderArtifacts `
                -Container $redisContainer -OrderIds $runOrderIds)
        Assert-RedisBoundaryBaseline
    }
    Get-ChildItem -LiteralPath $tokenRoot -Filter "$RunId-*.csv" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force

    [ordered]@{
        verdict = 'PASS'
        runId = $RunId
        sourceFingerprint = $sourceFingerprint
        actualOrders = 40000
        dataPreserved = [bool]$PreserveDataAfterPass
        timestampEvidence = 'final-timestamp-evidence.csv'
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $suiteVerdictPath -Encoding UTF8
    Save-State 'PASS' 'COMPLETE'
} catch {
    # 失败时保留订单、Token、JTL、SQL 和全部中间证据，禁止把失败结果清理后伪装成 PASS。
    [ordered]@{
        verdict = 'FAIL'
        runId = $RunId
        sourceFingerprint = $sourceFingerprint
        message = $_.Exception.Message
        failedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $suiteVerdictPath -Encoding UTF8
    Save-State 'FAIL' 'STOPPED' $_.Exception.Message
    throw
}
