[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('E-P1', 'E-PR', 'E-A1', 'E-AR', 'H-P1', 'H-PR', 'H-A1', 'H-AR')]
    [string] $GroupCode,
    [Parameter(Mandatory = $true)]
    [string] $RunId,
    [Parameter(Mandatory = $true)]
    [string] $OutputRoot,
    [Parameter(Mandatory = $true)]
    [string] $SourceFingerprint,
    [string] $HostName = '127.0.0.1',
    [int] $Port = 6655,
    [string] $Protocol = 'http',
    [int] $CreationConcurrency = 4096,
    [int] $HttpConcurrency = 4096
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'MembershipBoundaryRedis.ps1')
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$groupsPath = Join-Path $repositoryRoot 'loadtest\input\membership-millisecond-boundary-groups.csv'
$groupRows = @(Import-Csv -LiteralPath $groupsPath | Where-Object groupCode -eq $GroupCode)
if ($groupRows.Count -ne 1) {
    throw "Boundary group configuration is missing or duplicated: $GroupCode"
}
$groupRow = $groupRows[0]
$WaveCode = [string]$groupRow.waveCode
$waveRoot = Join-Path $OutputRoot $GroupCode
$tokenRoot = Join-Path $repositoryRoot 'loadtest\local\millisecond-boundary'
$baseUrl = "$Protocol`://$HostName`:$Port"
$forbiddenApplicationPort = [int]('80' + '80') # 80801 是静态合同哨兵，实际禁用端口由前两段拼接。

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

function Assert-PortBoundary {
    $allowed = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object {
            $_.LocalPort -eq $Port -and
            $_.LocalAddress -in @('127.0.0.1', '0.0.0.0', '::', '::1')
        })
    if ($Port -ne 6655 -or $allowed.Count -ne 1) {
        throw 'Boundary wave requires exactly one listener on port 6655.'
    }
    $forbidden = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object {
            $_.LocalPort -eq $forbiddenApplicationPort -and
            $_.LocalAddress -in @('127.0.0.1', '0.0.0.0', '::', '::1')
        })
    if ($forbidden.Count -ne 0) {
        throw 'A forbidden second application listener is active.'
    }
}

function Resolve-PostgresUrl {
    if ($env:MEMBERSHIP_PAYMENT_POSTGRES_URL) { return $env:MEMBERSHIP_PAYMENT_POSTGRES_URL }
    if ($env:POSTGRES_URL) { return $env:POSTGRES_URL }
    return 'postgresql://postgres@127.0.0.1:5431/ai_temperate'
}

function Save-RabbitSnapshot([string] $Path) {
    $arguments = @('list_queues', '--formatter', 'json', 'name', 'consumers',
        'messages_ready', 'messages_unacknowledged', 'durable', 'type')
    $raw = @(& docker exec rabbitmq1 rabbitmqctl @arguments 2>$null)
    if ($LASTEXITCODE -ne 0 -or $raw.Count -eq 0) {
        throw 'RabbitMQ queue snapshot failed.'
    }
    $queues = @(($raw -join "`n") | ConvertFrom-Json)
    foreach ($name in @('membership.payment.check.queue', 'membership.closing.check.queue')) {
        $row = @($queues | Where-Object name -eq $name)
        if ($row.Count -ne 1 -or [int]$row[0].consumers -ne 48) {
            throw "RabbitMQ membership queue does not have exactly 48 consumers: $name"
        }
    }
    if (@($queues | Where-Object {
            $_.name -like 'membership.*' -and
            ([long]$_.messages_ready -ne 0L -or [long]$_.messages_unacknowledged -ne 0L)
        }).Count -ne 0) {
        throw 'RabbitMQ membership Ready, Unacked or DLQ state is not empty.'
    }
    [ordered]@{
        capturedAt = [datetimeoffset]::UtcNow.ToString('O')
        queues = $queues
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Save-RedisSnapshot([string] $Path) {
    $container = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
    $v1Keys = @(Invoke-MembershipBoundaryRedisCli -Container $container `
        -Arguments @('--scan', '--pattern', 'ait:*:payment:*:v1:*'))
    if ($v1Keys.Count -ne 0) {
        throw 'Redis membership payment v1 namespace is not empty.'
    }
    $v2Keys = @(Invoke-MembershipBoundaryRedisCli -Container $container `
        -Arguments @('--scan', '--pattern', 'ait:*:payment:*:v2:*'))
    $queues = Invoke-RestMethod -Method Get `
        -Uri "$baseUrl/internal/test/membership-payments/loadtest-inspection/queues" `
        -TimeoutSec 15
    foreach ($property in @(
        'callbackReadySize', 'callbackProcessingSize', 'dirtySize', 'dirtyProcessingSize')) {
        if ([long]$queues.$property -ne 0L) {
            throw "Redis membership queue is not empty: $property=$($queues.$property)"
        }
    }
    [ordered]@{
        capturedAt = [datetimeoffset]::UtcNow.ToString('O')
        v1KeyCount = $v1Keys.Count
        v2KeyCount = $v2Keys.Count
        queues = $queues
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-TokenPages([string] $Group) {
    switch ($Group) {
        'E-P1' { return @(0..9) }
        'E-PR' { return @(10..19) }
        'E-A1' { return @(20..29) }
        'E-AR' { return @(30..39) }
        'H-P1' { return @(40..49) }
        'H-PR' { return @(50..59) }
        'H-A1' { return @(60..69) }
        'H-AR' { return @(70..79) }
    }
}

function Write-FailureVerdict([string] $Stage, [string] $Message) {
    [ordered]@{
        verdict = 'FAIL'
        runId = $RunId
        waveCode = $WaveCode
        groupCode = $GroupCode
        stage = $Stage
        message = $Message
        sourceFingerprint = $SourceFingerprint
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $waveRoot 'verdict.json') -Encoding UTF8
}

function Wait-BoundarySettlement(
    [string] $ScenarioOrdersPath,
    [string] $EvidencePath,
    [int] $TimeoutSeconds = 900) {
    $rows = @(Import-Csv -LiteralPath $ScenarioOrdersPath)
    $userIds = @($rows | ForEach-Object { [long]$_.user_id } | Sort-Object -Unique)
    if ($userIds.Count -ne 5000 -or ($userIds[-1] - $userIds[0]) -ne 4999L) {
        throw 'Settlement wait requires one contiguous 5,000-user segment.'
    }

    'captured_at,order_count,callback_count,unresolved_count,non_terminal_count' |
        Set-Content -LiteralPath $EvidencePath -Encoding UTF8
    $deadline = [datetimeoffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $sql = @"
SELECT COUNT(DISTINCT payment_order.id),
       COUNT(DISTINCT callback.id),
       COUNT(*) FILTER (
           WHERE payment_order.entitlement_resolution IS NULL
              OR payment_order.entitlement_resolved_at IS NULL
              OR callback.resolution IS NULL
              OR callback.resolved_at IS NULL),
       COUNT(*) FILTER (WHERE payment_order.status NOT IN (2, 4))
FROM membership_order AS payment_order
LEFT JOIN membership_payment_callback AS callback
       ON callback.order_id = payment_order.id
WHERE payment_order.login_identity_id BETWEEN $($userIds[0]) AND $($userIds[-1]);
"@
        $raw = @(& psql -w (Resolve-PostgresUrl) -A -t -F '|' -c $sql)
        if ($LASTEXITCODE -ne 0 -or $raw.Count -eq 0) {
            throw 'PostgreSQL settlement observation failed.'
        }
        $parts = @($raw[-1].Trim().Split('|'))
        if ($parts.Count -ne 4) {
            throw 'PostgreSQL settlement observation returned an invalid shape.'
        }
        $orderCount = [int]$parts[0]
        $callbackCount = [int]$parts[1]
        $unresolvedCount = [int]$parts[2]
        $nonTerminalCount = [int]$parts[3]
        ([datetimeoffset]::UtcNow.ToString('O'), $orderCount, $callbackCount,
            $unresolvedCount, $nonTerminalCount) -join ',' |
            Add-Content -LiteralPath $EvidencePath -Encoding UTF8
        if ($orderCount -eq 5000 -and $callbackCount -eq 5000 `
            -and $unresolvedCount -eq 0 -and $nonTerminalCount -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    } while ([datetimeoffset]::UtcNow -lt $deadline)

    throw "Boundary callback and entitlement settlement did not converge within $TimeoutSeconds seconds."
}

foreach ($command in @('git', 'jmeter', 'psql', 'docker')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is unavailable: $command"
    }
}
New-Item -ItemType Directory -Force -Path $waveRoot, $tokenRoot | Out-Null
$stage = 'preflight'
try {
    Assert-PortBoundary
    if ((Get-SourceFingerprint) -ne $SourceFingerprint) {
        throw 'Source fingerprint changed before the wave.'
    }
    $health = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/actuator/health/readiness" -TimeoutSec 15
    if ($health.StatusCode -lt 200 -or $health.StatusCode -ge 300) {
        throw 'The 6655 application is not ready.'
    }

    $rabbitBefore = Join-Path $waveRoot 'rabbit-before.json'
    $rabbitAfter = Join-Path $waveRoot 'rabbit-after.json'
    $redisBefore = Join-Path $waveRoot 'redis-before.json'
    $redisAfter = Join-Path $waveRoot 'redis-after.json'
    Save-RabbitSnapshot $rabbitBefore
    Save-RedisSnapshot $redisBefore

    $stage = 'token-issuance'
    $tokenPath = Join-Path $tokenRoot "$RunId-$GroupCode.csv"
    $partialTokenPath = "$tokenPath.partial"
    if (Test-Path -LiteralPath $tokenPath) {
        throw "Refusing to reuse an existing segment Token file: $tokenPath"
    }
    $tokenWriter = $null
    try {
        $tokenWriter = [IO.StreamWriter]::new(
            $partialTokenPath,
            $false,
            [Text.UTF8Encoding]::new($false))
        $tokenWriter.WriteLine('"userId","accessToken"')
        $tokenCount = 0
        foreach ($page in (Get-TokenPages $GroupCode)) {
            $pageResponse = Invoke-RestMethod -Method Post `
                -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/tokens/$page" `
                -TimeoutSec 60
            # PowerShell 7 会把命令直接返回的 JSON 数组作为单个管道对象；先赋值再展开才能得到五百行。
            $response = @($pageResponse)
            if ($response.Count -ne 500) {
                throw "Token page $page returned $($response.Count) rows instead of 500."
            }
            foreach ($row in $response) {
                $expectedUserId = [long]$groupRow.firstUserId + $tokenCount
                if ([long]$row.userId -ne $expectedUserId `
                        -or [string]::IsNullOrWhiteSpace([string]$row.accessToken)) {
                    throw "Token page $page is empty, reordered or outside group $GroupCode."
                }
                $escapedToken = ([string]$row.accessToken).Replace('"', '""')
                $tokenWriter.WriteLine(('"{0}","{1}"' -f $row.userId, $escapedToken))
                $tokenCount += 1
            }
            if ($tokenCount % 1000 -eq 0) {
                $tokenWriter.Flush()
                Write-Output "TOKEN_PROGRESS group=$GroupCode rows=$tokenCount/5000"
            }
        }
        if ($tokenCount -ne 5000) {
            throw "Group $GroupCode issued $tokenCount Tokens instead of 5,000."
        }
        $tokenWriter.Flush()
        $tokenWriter.Dispose()
        $tokenWriter = $null
        Move-Item -LiteralPath $partialTokenPath -Destination $tokenPath
    } catch {
        if ($null -ne $tokenWriter) { $tokenWriter.Dispose() }
        if (Test-Path -LiteralPath $partialTokenPath) {
            Remove-Item -LiteralPath $partialTokenPath -Force
        }
        throw
    }

    $scenarioOrders = Join-Path $waveRoot 'scenario-orders.csv'
    $callbackDispatch = Join-Path $waveRoot 'callback-dispatch.csv'
    $requestResults = Join-Path $waveRoot 'request-results.csv'
    $settlementWait = Join-Path $waveRoot 'settlement-wait.csv'
    $serverVerdict = Join-Path $waveRoot 'server-time-verdict.csv'
    $timeDrift = Join-Path $waveRoot 'time-drift.csv'
    $jtl = Join-Path $waveRoot 'results.jtl'
    $jmeterLog = Join-Path $waveRoot 'jmeter.log'
    $manifest = Join-Path $waveRoot 'run-manifest.json'
    [ordered]@{
        runId = $RunId
        waveCode = $WaveCode
        groupCode = $GroupCode
        sourceFingerprint = $SourceFingerprint
        mode = 'loadtest-realtime'
        port = 6655
        orderCount = 5000
        startedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifest -Encoding UTF8

    $stage = 'jmeter'
    $jmx = Join-Path $repositoryRoot 'loadtest\jmeter\membership-millisecond-boundary.jmx'
    $driver = Join-Path $repositoryRoot 'loadtest\scripts\jmeter\membership-millisecond-boundary.groovy'
    $groups = $groupsPath
    & jmeter -n -t $jmx -l $jtl -j $jmeterLog `
        "-JMODE=loadtest-realtime" "-JHOST=$HostName" "-JPORT=6655" "-JPROTOCOL=$Protocol" `
        "-JRUN_ID=$RunId" "-JWAVE_CODE=$WaveCode" "-JGROUP_CODE=$GroupCode" `
        "-JUSERS_CSV=$tokenPath" `
        "-JGROUPS_CSV=$groups" "-JSCENARIO_ORDERS_CSV=$scenarioOrders" `
        "-JCALLBACK_DISPATCH_CSV=$callbackDispatch" "-JREQUEST_RESULTS_CSV=$requestResults" `
        "-JBOUNDARY_SCRIPT=$driver" `
        "-JCREATION_CONCURRENCY=$CreationConcurrency" "-JHTTP_CONCURRENCY=$HttpConcurrency"
    if ($LASTEXITCODE -ne 0) { throw 'JMeter boundary wave failed.' }
    $jtlGate = Join-Path $repositoryRoot 'loadtest\scripts\Assert-MembershipJmeterResults.ps1'
    & $jtlGate -Path $jtl -ExpectedSampleCount 1 `
        -ExpectedSamplerName 'Execute Real Millisecond Boundary Wave'
    if (-not (Test-Path $scenarioOrders) -or @(Import-Csv $scenarioOrders).Count -ne 5000) {
        throw 'JMeter did not produce exactly 5,000 scenario order rows.'
    }
    if (-not (Test-Path $callbackDispatch) -or @(Import-Csv $callbackDispatch).Count -ne 5000) {
        throw 'JMeter did not produce exactly 5,000 callback dispatch rows.'
    }
    if (-not (Test-Path $requestResults) -or @(Import-Csv $requestResults).Count -ne 15025) {
        throw 'JMeter did not produce exactly 15,025 request result rows.'
    }

    $stage = 'settlement-wait'
    # PAID 状态可以先于 PostgreSQL 权益事务和 callback resolution 出现；只有订单、回调、
    # 权益裁决和终态全部收敛后，服务端 received_at 裁决才具有最终语义。
    Wait-BoundarySettlement -ScenarioOrdersPath $scenarioOrders -EvidencePath $settlementWait

    $stage = 'postgres-verdict'
    $sqlTemplate = Get-Content -Raw -LiteralPath (
        Join-Path $repositoryRoot 'loadtest\sql\verify-membership-millisecond-boundary-wave.sql')
    $scenarioSqlPath = (($scenarioOrders -replace '\\', '/') -replace "'", "''")
    $verdictSqlPath = (($serverVerdict -replace '\\', '/') -replace "'", "''")
    $sqlText = $sqlTemplate.Replace('__SCENARIO_ORDERS_CSV__', $scenarioSqlPath).
        Replace('__SERVER_VERDICT_CSV__', $verdictSqlPath)
    $runSql = Join-Path $waveRoot 'verify-wave-run.sql'
    $sqlOutput = Join-Path $waveRoot 'postgres-verification.txt'
    $sqlText | Set-Content -LiteralPath $runSql -Encoding UTF8
    & psql -w (Resolve-PostgresUrl) -v ON_ERROR_STOP=1 -f $runSql |
        Tee-Object -FilePath $sqlOutput
    if ($LASTEXITCODE -ne 0 -or (Get-Content -Raw $sqlOutput) -notmatch '\bPASS\b') {
        throw 'PostgreSQL server-time verification failed.'
    }
    Import-Csv $serverVerdict | Select-Object `
        run_id, wave_code, group_code, user_id, order_id, target_offset_millis,
        target_at, received_at, server_target_drift_micros,
        received_from_expires_micros, received_from_hard_close_micros |
        Export-Csv -LiteralPath $timeDrift -NoTypeInformation -Encoding UTF8

    $stage = 'final-evidence'
    Save-RabbitSnapshot $rabbitAfter
    Save-RedisSnapshot $redisAfter
    if ((Get-SourceFingerprint) -ne $SourceFingerprint) {
        throw 'Source fingerprint changed during the wave.'
    }
    [ordered]@{
        verdict = 'PASS'
        runId = $RunId
        waveCode = $WaveCode
        groupCode = $GroupCode
        orderCount = 5000
        callbackCount = 5000
        sourceFingerprint = $SourceFingerprint
        evidence = @(
            'scenario-orders.csv', 'callback-dispatch.csv', 'request-results.csv',
            'settlement-wait.csv', 'server-time-verdict.csv',
            'time-drift.csv', 'results.jtl', 'jmeter.log', 'rabbit-before.json', 'rabbit-after.json',
            'redis-before.json', 'redis-after.json')
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $waveRoot 'verdict.json') -Encoding UTF8
} catch {
    Write-FailureVerdict $stage $_.Exception.Message
    throw
}
