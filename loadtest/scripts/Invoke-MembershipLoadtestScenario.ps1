[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $Scenario,
    [Parameter(Mandatory = $true)] [string] $Jmx,
    [ValidateSet('loadtest-realtime')] [string] $Mode = 'loadtest-realtime',
    [string] $HostName = 'localhost',
    [int] $Port = 6655,
    [string] $Protocol = 'http',
    [string] $UsersCsv = 'loadtest/local/loadtest-users.csv',
    [string] $OutputRoot = 'loadtest-output/runs',
    [int] $Threads = 4,
    [int] $RampUp = 1,
    [int] $Concurrency = 0,
    [int] $SettleSeconds = 40,
    [switch] $Cleanup,
    [string] $OrderId = '',
    [string] $Money = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require-Command([string] $Name, [string] $Alternative = '') {
    if (Get-Command $Name -ErrorAction SilentlyContinue) { return }
    if ($Alternative -and (Get-Command $Alternative -ErrorAction SilentlyContinue)) { return }
    throw "Required command is unavailable: $Name"
}

function Get-ApprovedUserIds() {
    return @(
        '72659006262480896',
        '73014701344296960',
        '74891801495998464',
        '76721355290185728',
        '84736921162616832',
        '84739559597936640',
        '84742296792338432',
        '84745417706835968',
        '84746552547086336',
        '84753114204344320',
        '84754367089086464',
        '84755204414771200',
        '84758509811535872',
        '84758866549673984',
        '84759380653903872',
        '84760794662834176'
    )
}

function Get-MinimumTestCases([string] $ScenarioName) {
    switch ($ScenarioName) {
        'membership-auth-boundary' { return 8 }
        'membership-order-state-machine' { return 30 }
        'membership-entitlement-resolution-matrix' { return 40 }
        'membership-order-concurrency' { return 5 }
        'membership-long-observation' { return 12 }
        'membership-callback-transport' { return 15 }
        'membership-callback-race-idempotency' { return 8 }
        'membership-callback-identity' { return 5 }
        'membership-rabbit-state-timing' { return 11 }
        'membership-marker-stage-matrix' { return 28 }
        'membership-rejected-closing-matrix' { return 10 }
        'membership-persistence-batch' { return 6 }
        'membership-recovery-terminal-cleanup' { return 12 }
        default { throw "Unknown membership loadtest scenario: $ScenarioName" }
    }
}

function Get-ScenarioTestCaseCount(
    [string] $ScenarioName,
    [string] $RepositoryRoot,
    [int] $ThreadCount) {
    switch ($ScenarioName) {
        'membership-order-concurrency' {
            return @(Import-Csv -LiteralPath (
                    Join-Path $RepositoryRoot 'loadtest/input/membership-order-concurrency-cases.csv')).Count
        }
        default { return $ThreadCount }
    }
}

function Get-ExpectedEvidenceRows([string] $ScenarioName) {
    switch ($ScenarioName) {
        'membership-order-state-machine' { return 30 }
        'membership-entitlement-resolution-matrix' { return 40 }
        'membership-callback-transport' { return 15 }
        'membership-callback-identity' { return 5 }
        'membership-rabbit-state-timing' { return 11 }
        'membership-marker-stage-matrix' { return 28 }
        'membership-order-concurrency' { return 25 }
        'membership-recovery-terminal-cleanup' { return 12 }
        'membership-rejected-closing-matrix' { return 10 }
        'membership-long-observation' { return 12 }
        default { return 0 }
    }
}

function Ensure-LoadtestUsersCsv([string] $Path) {
    if (Test-Path -LiteralPath $Path) { return }
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    # 首次运行建立十六个已批准账号的占位清单；真实 Token 仍必须由应用签名端点签发。
    $userRows = @()
    foreach ($userId in (Get-ApprovedUserIds)) {
        $userRows += [pscustomobject]@{
            userId = $userId
            accessToken = ''
        }
    }
    $userRows | Export-Csv -LiteralPath $Path -NoTypeInformation -Encoding UTF8
}

function Read-RequiredUsers([string] $Path, [switch] $RequireTokens) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Token CSV is missing: $Path" }
    $rows = @(Import-Csv -LiteralPath $Path)
    $required = @(Get-ApprovedUserIds)
    $actual = @($rows | ForEach-Object { [string]$_.userId })
    if ($actual.Count -ne $required.Count -or @($required | Where-Object { $actual -notcontains $_ }).Count -ne 0) {
        throw 'Token CSV must contain exactly the sixteen approved existing user IDs.'
    }
    if ($RequireTokens -and @($rows | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.accessToken) }).Count -gt 0) {
        throw 'Token CSV contains an empty accessToken; the runner never creates or modifies users.'
    }
    return $rows
}

function Test-ApplicationHealth([string] $BaseUrl) {
    foreach ($path in @('/actuator/health/readiness', '/api/health')) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + $path) -TimeoutSec 10
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) { return }
        } catch { }
    }
    throw "Application readiness probe failed: $BaseUrl"
}

function Test-MembershipPaymentTables([string] $ConnectionString) {
    $query = "select count(*) from information_schema.tables where table_schema = 'public' and table_name in ('membership_order','membership_payment_callback');"
    $tableCount = @(& psql -w $ConnectionString -v ON_ERROR_STOP=1 -Atc $query 2>$null)
    if ($LASTEXITCODE -ne 0 -or $tableCount.Count -eq 0 -or [int]$tableCount[0].Trim() -ne 2) {
        throw 'PostgreSQL membership payment tables are not both available.'
    }
}

function Request-LoadtestTokens(
    [string] $BaseUrl,
    [string] $UsersPath,
    [string] $NegativeTokensPath) {
    try {
        $response = Invoke-RestMethod -Method Post `
            -Uri ($BaseUrl + '/internal/test/membership-payments/loadtest-tokens') `
            -TimeoutSec 10
    } catch {
        throw 'Application did not issue loadtest tokens through the local signed-token endpoint.'
    }

    $issued = @($response.users)
    $required = @(Get-ApprovedUserIds)
    $issuedIds = @($issued | ForEach-Object { [string]$_.userId })
    $missingIdCount = @($required | Where-Object { $issuedIds -notcontains $_ }).Count
    $emptyTokenCount = @($issued | Where-Object {
        [string]::IsNullOrWhiteSpace([string]$_.accessToken)
    }).Count
    $issuedShapeIsValid = ($issued.Count -eq $required.Count) -and ($missingIdCount -eq 0) -and ($emptyTokenCount -eq 0)
    if (-not $issuedShapeIsValid) {
        throw 'Local token endpoint did not return exactly the sixteen approved users with non-empty tokens.'
    }

    $tokenByUserId = @{}
    foreach ($entry in $issued) {
        $tokenByUserId[[string]$entry.userId] = [string]$entry.accessToken
    }
    $currentRows = @(Read-RequiredUsers $UsersPath)
    $updatedRows = @($currentRows | ForEach-Object {
        [pscustomobject]@{
            userId = [string]$_.userId
            accessToken = $tokenByUserId[[string]$_.userId]
        }
    })
    # CSV 位于 Git 忽略的 local 目录；Token 只落盘供本次 JMeter 读取，不复制到运行产物或日志。
    $updatedRows | Export-Csv -LiteralPath $UsersPath -NoTypeInformation -Encoding UTF8

    if ([string]::IsNullOrWhiteSpace([string]$response.expiredAccessToken) `
        -or [string]::IsNullOrWhiteSpace([string]$response.nonAllowlistedUser.accessToken)) {
        throw 'Local token endpoint did not return the required negative authentication tokens.'
    }
    # 负向 Token 与四个用户 Token 一样只写 Git 忽略目录，运行产物只记录用例结论。
    [ordered]@{
        expiredAccessToken = [string]$response.expiredAccessToken
        nonAllowlistedUserId = [string]$response.nonAllowlistedUser.userId
        nonAllowlistedAccessToken = [string]$response.nonAllowlistedUser.accessToken
    } | ConvertTo-Json | Set-Content -LiteralPath $NegativeTokensPath -Encoding UTF8
}

function Invoke-RabbitCtlJson([string[]] $Arguments) {
    if ($Arguments.Count -lt 1) {
        throw 'RabbitMQ JSON query requires one rabbitmqctl command.'
    }
    # rabbitmqctl 的 formatter 选项必须放在子命令之后、字段列表之前，避免把选项误解析为输出列。
    $rabbitArguments = @($Arguments[0], '--formatter', 'json')
    if ($Arguments.Count -gt 1) {
        $rabbitArguments += @($Arguments[1..($Arguments.Count - 1)])
    }
    $command = Get-Command rabbitmqctl -ErrorAction SilentlyContinue
    $raw = @()
    $querySucceeded = $false
    if ($null -ne $command) {
        $raw = @(& $command.Source @rabbitArguments 2>$null)
        $querySucceeded = $LASTEXITCODE -eq 0 -and $raw.Count -gt 0
    } else {
        $container = if ($env:RABBITMQ_CONTAINER) { $env:RABBITMQ_CONTAINER } else { 'rabbitmq1' }
        $raw = @(& docker exec $container rabbitmqctl @rabbitArguments 2>$null)
        $querySucceeded = $LASTEXITCODE -eq 0 -and $raw.Count -gt 0
    }
    if ($querySucceeded) {
        return @(($raw -join "`n") | ConvertFrom-Json)
    }

    # 受限测试终端可能无法访问 Docker 命名管道；此时复用同一 RabbitMQ Management API
    # 采集只读拓扑和基线，不能因为执行环境变化而跳过 durable、quorum、DLQ 验收。
    $rabbitUser = if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_USERNAME)) {
        'appuser'
    } else {
        $env:RABBITMQ_USERNAME
    }
    $rabbitPassword = if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_PASSWORD)) {
        'Rabbit_Strong_2026'
    } else {
        $env:RABBITMQ_PASSWORD
    }
    $managementBaseUrl = if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_MANAGEMENT_URL)) {
        'http://127.0.0.1:15673'
    } else {
        $env:RABBITMQ_MANAGEMENT_URL.TrimEnd('/')
    }
    $basicToken = [Convert]::ToBase64String(
        [Text.Encoding]::ASCII.GetBytes("$rabbitUser`:$rabbitPassword"))
    $headers = @{ Authorization = "Basic $basicToken" }
    try {
        switch ($Arguments[0]) {
            'list_queues' {
                $response = Invoke-RestMethod -Method Get `
                    -Uri ($managementBaseUrl + '/api/queues/%2F') `
                    -Headers $headers -TimeoutSec 10
                return @($response | Select-Object `
                    name, durable, type, arguments, consumers,
                    messages_ready, messages_unacknowledged)
            }
            'list_exchanges' {
                $response = Invoke-RestMethod -Method Get `
                    -Uri ($managementBaseUrl + '/api/exchanges/%2F') `
                    -Headers $headers -TimeoutSec 10
                return @($response | Select-Object `
                    name, type, durable, auto_delete, arguments)
            }
            default {
                throw "Unsupported RabbitMQ Management API query: $($Arguments[0])"
            }
        }
    } catch {
        throw "RabbitMQ JSON query failed through CLI and Management API: $($Arguments -join ' ')"
    }
}

function Save-RabbitSnapshot([string] $Path) {
    $queues = @(Invoke-RabbitCtlJson @(
        'list_queues', 'name', 'durable', 'type', 'arguments',
        'consumers', 'messages_ready', 'messages_unacknowledged'))
    $exchanges = @(Invoke-RabbitCtlJson @(
        'list_exchanges', 'name', 'type', 'durable', 'auto_delete', 'arguments'))
    [ordered]@{
        capturedAt = [DateTimeOffset]::UtcNow.ToString('O')
        queues = $queues
        exchanges = $exchanges
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-RabbitArgumentValue($Arguments, [string] $Name) {
    if ($null -eq $Arguments) {
        return $null
    }
    # rabbitmqctl 不同版本会把 arguments 输出为 JSON 对象或三元组数组，验收器必须兼容两种稳定格式。
    $property = $Arguments.PSObject.Properties[$Name]
    if ($null -ne $property) {
        return $property.Value
    }
    foreach ($entry in @($Arguments)) {
        $parts = @($entry)
        if ($parts.Count -ge 3 -and [string]$parts[0] -eq $Name) {
            return $parts[2]
        }
    }
    return $null
}

function Assert-RabbitTopologyAndBaseline(
    [string] $BeforePath,
    [string] $AfterPath,
    [string] $ScenarioName,
    [string] $OutputPath) {
    $before = Get-Content -Raw -LiteralPath $BeforePath | ConvertFrom-Json
    $after = Get-Content -Raw -LiteralPath $AfterPath | ConvertFrom-Json
    $paymentQueue = 'membership.payment.check.queue'
    $closingQueue = 'membership.closing.check.queue'
    $paymentDlq = 'membership.payment.check.dlq'
    $closingDlq = 'membership.closing.check.dlq'
    $requiredQueues = @($paymentQueue, $closingQueue, $paymentDlq, $closingDlq)
    $requiredExchanges = @(
        @{ name = 'membership.payment.check.delay.exchange'; type = 'x-delayed-message' },
        @{ name = 'membership.closing.check.delay.exchange'; type = 'x-delayed-message' },
        @{ name = 'membership.payment.check.dlq.exchange'; type = 'direct' },
        @{ name = 'membership.closing.check.dlq.exchange'; type = 'direct' })

    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($queueName in $requiredQueues) {
        $queue = @($after.queues | Where-Object { $_.name -eq $queueName })
        if ($queue.Count -ne 1) {
            $failures.Add("missing-or-duplicate-queue:$queueName")
            continue
        }
        if (-not [bool]$queue[0].durable -or [string]$queue[0].type -ne 'quorum') {
            $failures.Add("queue-not-durable-quorum:$queueName")
        }
    }
    foreach ($queueName in @($paymentQueue, $closingQueue)) {
        $queueRows = @($after.queues | Where-Object { $_.name -eq $queueName })
        if ($queueRows.Count -ne 1) {
            continue
        }
        $queue = $queueRows[0]
        $arguments = $queue.arguments
        $deliveryLimit = Get-RabbitArgumentValue $arguments 'x-delivery-limit'
        $deadLetterExchange = Get-RabbitArgumentValue $arguments 'x-dead-letter-exchange'
        $deadLetterRoutingKey = Get-RabbitArgumentValue $arguments 'x-dead-letter-routing-key'
        if ($null -eq $arguments `
            -or [int]$deliveryLimit -ne 3 `
            -or [string]$deadLetterExchange -notmatch '\.dlq\.exchange$' `
            -or [string]$deadLetterRoutingKey -notmatch '\.dead$') {
            $failures.Add("invalid-finite-retry-arguments:$queueName")
        }
    }
    foreach ($expected in $requiredExchanges) {
        $exchange = @($after.exchanges | Where-Object { $_.name -eq $expected.name })
        if ($exchange.Count -ne 1 `
            -or -not [bool]$exchange[0].durable `
            -or [string]$exchange[0].type -ne $expected.type) {
            $failures.Add("invalid-exchange:$($expected.name)")
        }
    }

    foreach ($queueName in @($paymentQueue, $closingQueue, $closingDlq)) {
        $beforeRows = @($before.queues | Where-Object { $_.name -eq $queueName })
        $afterRows = @($after.queues | Where-Object { $_.name -eq $queueName })
        if ($beforeRows.Count -ne 1 -or $afterRows.Count -ne 1) {
            $failures.Add("queue-baseline-unavailable:$queueName")
            continue
        }
        $beforeQueue = $beforeRows[0]
        $afterQueue = $afterRows[0]
        if ([long]$afterQueue.messages_ready -ne [long]$beforeQueue.messages_ready `
            -or [long]$afterQueue.messages_unacknowledged -ne [long]$beforeQueue.messages_unacknowledged) {
            $failures.Add("queue-did-not-return-to-baseline:$queueName")
        }
    }

    $expectedDlqDelta = if ($ScenarioName -eq 'membership-rabbit-state-timing') { 1L } else { 0L }
    $actualDlqDelta = $null
    $beforePaymentDlqRows = @($before.queues | Where-Object { $_.name -eq $paymentDlq })
    $afterPaymentDlqRows = @($after.queues | Where-Object { $_.name -eq $paymentDlq })
    if ($beforePaymentDlqRows.Count -ne 1 -or $afterPaymentDlqRows.Count -ne 1) {
        $failures.Add("queue-baseline-unavailable:$paymentDlq")
    } else {
        $beforePaymentDlq = $beforePaymentDlqRows[0]
        $afterPaymentDlq = $afterPaymentDlqRows[0]
        $actualDlqDelta = [long]$afterPaymentDlq.messages_ready - [long]$beforePaymentDlq.messages_ready
        if ($actualDlqDelta -ne $expectedDlqDelta `
            -or [long]$afterPaymentDlq.messages_unacknowledged `
                -ne [long]$beforePaymentDlq.messages_unacknowledged) {
            $failures.Add("unexpected-payment-dlq-delta:$actualDlqDelta")
        }
    }

    $result = [ordered]@{
        verdict = if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
        expectedPaymentDlqDelta = $expectedDlqDelta
        actualPaymentDlqDelta = $actualDlqDelta
        failures = @($failures)
    }
    $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
    if ($failures.Count -gt 0) {
        throw "RabbitMQ verification failed: $($failures -join ', ')"
    }
}

function Get-LocalRedisPassword() {
    if (-not [string]::IsNullOrWhiteSpace($env:REDIS_PASSWORD)) {
        return $env:REDIS_PASSWORD
    }
    $localProfile = Join-Path $repoRoot 'ai-temperate-web/src/main/resources/application-local-dev.yml'
    if (-not (Test-Path -LiteralPath $localProfile)) { return '' }
    $insideRedis = $false
    foreach ($line in @(Get-Content -LiteralPath $localProfile)) {
        if ($line -match '^\s{4}redis:\s*$') {
            $insideRedis = $true
            continue
        }
        if ($insideRedis -and $line -match '^\s{2}\S') {
            break
        }
        if ($insideRedis -and $line -match '^\s{6}password:\s*"([^"]+)"') {
            return $Matches[1]
        }
    }
    return ''
}

function Invoke-RedisCli([string[]] $Arguments) {
    $command = Get-Command redis-cli -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        $previousAuth = $env:REDISCLI_AUTH
        try {
            $resolvedPassword = Get-LocalRedisPassword
            if (-not [string]::IsNullOrWhiteSpace($resolvedPassword)) {
                $env:REDISCLI_AUTH = $resolvedPassword
            }
            return @(& $command.Source @Arguments 2>$null)
        } finally {
            $env:REDISCLI_AUTH = $previousAuth
        }
    }

    $container = if ($env:REDIS_CONTAINER) { $env:REDIS_CONTAINER } else { 'redis7' }
    $dockerArguments = @('exec')
    $resolvedPassword = Get-LocalRedisPassword
    if (-not [string]::IsNullOrWhiteSpace($resolvedPassword)) {
        $dockerArguments += @('-e', "REDISCLI_AUTH=$resolvedPassword")
    }
    $dockerArguments += @($container, 'redis-cli') + $Arguments
    $result = @(& docker @dockerArguments 2>$null)
    if ($LASTEXITCODE -eq 0) { return $result }

    if ($Arguments.Count -ne 3 `
        -or $Arguments[0] -ne '--scan' `
        -or $Arguments[1] -ne '--pattern') {
        throw "Redis command failed for container $container and has no TCP fallback."
    }
    return @(Invoke-RedisScanOverTcp -Pattern $Arguments[2])
}

function Write-RedisRespCommand(
    [System.IO.Stream] $Stream,
    [string[]] $Parts) {
    $builder = [Text.StringBuilder]::new()
    [void]$builder.Append('*').Append($Parts.Count).Append("`r`n")
    foreach ($part in $Parts) {
        $bytes = [Text.Encoding]::UTF8.GetBytes($part)
        [void]$builder.Append('$').Append($bytes.Length).Append("`r`n")
        [void]$builder.Append($part).Append("`r`n")
    }
    $payload = [Text.Encoding]::UTF8.GetBytes($builder.ToString())
    $Stream.Write($payload, 0, $payload.Length)
    $Stream.Flush()
}

function Read-RedisRespLine([System.IO.Stream] $Stream) {
    $bytes = [System.Collections.Generic.List[byte]]::new()
    while ($true) {
        $value = $Stream.ReadByte()
        if ($value -lt 0) { throw 'Redis TCP response ended unexpectedly.' }
        if ($value -eq 13) {
            if ($Stream.ReadByte() -ne 10) { throw 'Redis TCP response has invalid line ending.' }
            return [Text.Encoding]::UTF8.GetString($bytes.ToArray())
        }
        $bytes.Add([byte]$value)
    }
}

function Read-RedisResp([System.IO.Stream] $Stream) {
    $prefixValue = $Stream.ReadByte()
    if ($prefixValue -lt 0) { throw 'Redis TCP response is empty.' }
    $prefix = [char]$prefixValue
    switch ($prefix) {
        '+' { return Read-RedisRespLine $Stream }
        '-' { throw "Redis TCP error: $(Read-RedisRespLine $Stream)" }
        ':' { return [long](Read-RedisRespLine $Stream) }
        '$' {
            $length = [int](Read-RedisRespLine $Stream)
            if ($length -lt 0) { return $null }
            $payload = [byte[]]::new($length)
            $offset = 0
            while ($offset -lt $length) {
                $read = $Stream.Read($payload, $offset, $length - $offset)
                if ($read -le 0) { throw 'Redis TCP bulk response ended unexpectedly.' }
                $offset += $read
            }
            if ($Stream.ReadByte() -ne 13 -or $Stream.ReadByte() -ne 10) {
                throw 'Redis TCP bulk response has invalid terminator.'
            }
            return [Text.Encoding]::UTF8.GetString($payload)
        }
        '*' {
            $count = [int](Read-RedisRespLine $Stream)
            if ($count -lt 0) { return $null }
            $items = [object[]]::new($count)
            for ($index = 0; $index -lt $count; $index++) {
                $items[$index] = Read-RedisResp $Stream
            }
            Write-Output -NoEnumerate $items
            return
        }
        default { throw "Unsupported Redis TCP response prefix: $prefix" }
    }
}

function Invoke-RedisScanOverTcp([string] $Pattern) {
    $redisHost = if ([string]::IsNullOrWhiteSpace($env:REDIS_HOST)) {
        '127.0.0.1'
    } else {
        $env:REDIS_HOST
    }
    $redisPort = if ([string]::IsNullOrWhiteSpace($env:REDIS_PORT)) {
        6378
    } else {
        [int]$env:REDIS_PORT
    }
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync($redisHost, $redisPort)
        if (-not $connect.Wait(5000)) { throw 'Redis TCP connection timed out.' }
        $stream = $client.GetStream()
        $stream.ReadTimeout = 10000
        $stream.WriteTimeout = 10000
        $password = Get-LocalRedisPassword
        if (-not [string]::IsNullOrWhiteSpace($password)) {
            Write-RedisRespCommand $stream @('AUTH', $password)
            $authResult = Read-RedisResp $stream
            if ([string]$authResult -ne 'OK') { throw 'Redis TCP authentication failed.' }
        }
        $cursor = '0'
        $keys = [System.Collections.Generic.List[string]]::new()
        do {
            Write-RedisRespCommand $stream @('SCAN', $cursor, 'MATCH', $Pattern, 'COUNT', '500')
            $response = Read-RedisResp $stream
            if ($response.Count -ne 2) { throw 'Redis SCAN returned an invalid response.' }
            $cursor = [string]$response[0]
            foreach ($key in @($response[1])) { $keys.Add([string]$key) }
        } while ($cursor -ne '0')
        return @($keys)
    } finally {
        $client.Dispose()
    }
}

function Save-RedisSnapshot([string] $BaseUrl, [string] $Path) {
    # SCAN 只负责确认命名空间中没有 Stream；四个队列大小由应用复用 RedisKeyFactory 精确读取。
    $keys = @(Invoke-RedisCli @('--scan', '--pattern', 'ait:*:payment:*'))
    $streamKeys = @(Invoke-RedisCli @('--scan', '--pattern', 'ait:*:payment:*stream*'))
    if ($streamKeys.Count -ne 0) { throw 'Redis Stream keys were found in the membership payment namespace.' }
    try {
        $queues = Invoke-RestMethod -Method Get `
            -Uri ($BaseUrl + '/internal/test/membership-payments/loadtest-control/queues') `
            -TimeoutSec 10
    } catch {
        throw 'Application did not expose the loopback Redis queue probe for the selected loadtest Profile.'
    }
    [ordered]@{
        capturedAt = [DateTimeOffset]::UtcNow.ToString('O')
        paymentKeyCount = $keys.Count
        streamKeyCount = $streamKeys.Count
        queues = $queues
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Wait-RedisQueuesAtOrBelowBaseline(
    [string] $BaseUrl,
    [string] $BeforePath,
    [int] $TimeoutSeconds = 120) {
    $before = Get-Content -Raw -LiteralPath $BeforePath | ConvertFrom-Json
    $queueNames = @(
        'callbackReadySize',
        'callbackProcessingSize',
        'dirtySize',
        'dirtyProcessingSize')
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $queues = Invoke-RestMethod -Method Get `
                -Uri ($BaseUrl + '/internal/test/membership-payments/loadtest-control/queues') `
                -TimeoutSec 10
        } catch {
            throw 'Application Redis queue probe failed while waiting for bounded settlement.'
        }
        $hasGrowth = $false
        foreach ($propertyName in $queueNames) {
            if ([long]$queues.$propertyName -gt [long]$before.queues.$propertyName) {
                $hasGrowth = $true
                break
            }
        }
        if (-not $hasGrowth) { return }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    # 超时后仍交给统一快照与断言输出精确集合名称，避免在等待函数中丢失诊断信息。
}

function Assert-RedisBaselineAndTerminalArtifacts(
    [string] $BaseUrl,
    [string] $BeforePath,
    [string] $AfterPath,
    [string] $ScenarioCsvPath,
    [string] $OutputPath) {
    $before = Get-Content -Raw -LiteralPath $BeforePath | ConvertFrom-Json
    $after = Get-Content -Raw -LiteralPath $AfterPath | ConvertFrom-Json
    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($propertyName in @(
        'callbackReadySize',
        'callbackProcessingSize',
        'dirtySize',
        'dirtyProcessingSize')) {
        # 运行期间消费者可能顺带排空基线中已有的旧成员；只有测试结束后高于基线才代表本次新增了积压。
        if ([long]$after.queues.$propertyName -gt [long]$before.queues.$propertyName) {
            $failures.Add("queue-exceeds-baseline:$propertyName")
        }
    }
    if ([long]$before.streamKeyCount -ne 0L -or [long]$after.streamKeyCount -ne 0L) {
        $failures.Add('redis-stream-key-detected')
    }

    $orderIds = @()
    if (Test-Path -LiteralPath $ScenarioCsvPath) {
        $orderIds = @(Import-Csv -LiteralPath $ScenarioCsvPath |
            Where-Object {
                $_.PSObject.Properties.Name -contains 'order_id' `
                    -and -not [string]::IsNullOrWhiteSpace([string]$_.order_id)
            } |
            ForEach-Object { [string]$_.order_id } |
            Sort-Object -Unique)
    }
    $artifactRows = [System.Collections.Generic.List[object]]::new()
    for ($offset = 0; $offset -lt $orderIds.Count; $offset += 250) {
        $last = [Math]::Min($offset + 249, $orderIds.Count - 1)
        $batch = @($orderIds[$offset..$last])
        $body = @{ orderIds = $batch } | ConvertTo-Json -Depth 3 -Compress
        try {
            # PowerShell 7 会把 JSON 顶层数组直接反序列化为 Object[]；这里不能再用 @(...)
            # 包裹命令，否则整个批次会成为单个嵌套数组，进而把所有 false 字段误判为 true。
            $response = Invoke-RestMethod -Method Post `
                -Uri ($BaseUrl + '/internal/test/membership-payments/loadtest-control/state-batch') `
                -ContentType 'application/json' `
                -Body $body `
                -TimeoutSec 30
        } catch {
            throw 'Application Redis terminal artifact batch probe failed.'
        }
        foreach ($row in $response) {
            $artifactRows.Add($row)
        }
    }
    if ($artifactRows.Count -ne $orderIds.Count) {
        $failures.Add("artifact-result-count-mismatch:$($artifactRows.Count)/$($orderIds.Count)")
    }
    $returnedIds = @($artifactRows | ForEach-Object { [string]$_.orderId } | Sort-Object -Unique)
    if (($returnedIds -join ',') -ne (($orderIds | Sort-Object) -join ',')) {
        $failures.Add('artifact-result-order-id-set-mismatch')
    }
    foreach ($row in $artifactRows) {
        if ([bool]$row.snapshotPresent) {
            $failures.Add("terminal-snapshot-present:$($row.orderId)")
        }
        if ([bool]$row.callbackMarkerPresent) {
            $failures.Add("terminal-callback-marker-present:$($row.orderId)")
        }
    }

    $result = [ordered]@{
        verdict = if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
        queuesBefore = $before.queues
        queuesAfter = $after.queues
        terminalOrdersChecked = $orderIds.Count
        terminalArtifacts = @($artifactRows)
        streamKeyCountBefore = [long]$before.streamKeyCount
        streamKeyCountAfter = [long]$after.streamKeyCount
        failures = @($failures)
    }
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
    if ($failures.Count -gt 0) {
        throw "Redis verification failed: $($failures -join ', ')"
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jmxPath = (Resolve-Path (Join-Path $repoRoot $Jmx)).Path
$usersPathCandidate = if ([System.IO.Path]::IsPathRooted($UsersCsv)) {
    $UsersCsv
} else {
    Join-Path $repoRoot $UsersCsv
}
Ensure-LoadtestUsersCsv $usersPathCandidate
$usersPath = (Resolve-Path $usersPathCandidate).Path
$negativeTokensPath = Join-Path (Split-Path -Parent $usersPath) 'loadtest-auth-negative.json'
$minimumTestCases = Get-MinimumTestCases $Scenario
$actualTestCases = Get-ScenarioTestCaseCount $Scenario $repoRoot $Threads
if ($actualTestCases -lt $minimumTestCases) {
    throw "Scenario $Scenario requires at least $minimumTestCases independent test cases; received $actualTestCases."
}
$rows = Read-RequiredUsers $usersPath
$baseUrl = "$Protocol`://$HostName`:$Port"
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runVariant = if ($Concurrency -gt 0) { "-c$Concurrency" } else { '' }
$runId = "$runStamp-$Mode-$Scenario$runVariant"
$runDir = New-Item -ItemType Directory -Force -Path (Join-Path $repoRoot "$OutputRoot\$runId")
$runDirPath = $runDir.FullName
$jtlPath = Join-Path $runDirPath "$Scenario.jtl"
$logPath = Join-Path $runDirPath 'jmeter.log'
$reportPath = Join-Path $runDirPath 'html-report'
$summaryPath = Join-Path $runDirPath 'summary.csv'
$scenarioCsvPath = Join-Path $runDirPath 'scenario-orders.csv'
$sqlOutputPath = Join-Path $runDirPath 'sql-verification.txt'
$redisBeforePath = Join-Path $runDirPath 'redis-before.json'
$redisAfterPath = Join-Path $runDirPath 'redis-after.json'
$redisVerificationPath = Join-Path $runDirPath 'redis-verification.json'
$rabbitBeforePath = Join-Path $runDirPath 'rabbit-before.json'
$rabbitAfterPath = Join-Path $runDirPath 'rabbit-after.json'
$rabbitVerificationPath = Join-Path $runDirPath 'rabbit-verification.json'
$verdictPath = Join-Path $runDirPath 'verdict.json'
$timeDeviationPath = Join-Path $runDirPath 'time-deviation.csv'

$currentStage = 'preflight'
$cleanupPerformed = $false
try {
    Require-Command 'jmeter'
    Require-Command 'java'
    Require-Command 'mvn'
    Require-Command 'psql'
    Require-Command 'docker'

$activeProfiles = @($env:SPRING_PROFILES_ACTIVE -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($Mode -ne 'loadtest-realtime' -or $activeProfiles -notcontains 'loadtest-realtime') {
    throw 'Membership payment integration runners require SPRING_PROFILES_ACTIVE=loadtest-realtime.'
}
foreach ($switchName in @('MEMBERSHIP_PAYMENT_ENABLED', 'SIMULATED_PAYMENT_ENABLED', 'MEMBERSHIP_PAYMENT_LOADTEST_ENABLED')) {
    $configuredSwitch = [Environment]::GetEnvironmentVariable($switchName)
    $explicitSwitchIsDisabled = (-not [string]::IsNullOrWhiteSpace($configuredSwitch)) -and ($configuredSwitch.Trim().ToLowerInvariant() -ne 'true')
    if ($explicitSwitchIsDisabled) {
        throw "$switchName must not explicitly disable the selected loadtest Profile."
    }
}
$postgresUrl = if ([string]::IsNullOrWhiteSpace($env:MEMBERSHIP_PAYMENT_POSTGRES_URL)) {
    if ([string]::IsNullOrWhiteSpace($env:POSTGRES_URL)) {
        'postgresql://postgres@127.0.0.1:5431/ai_temperate'
    } else {
        $env:POSTGRES_URL
    }
} else {
    $env:MEMBERSHIP_PAYMENT_POSTGRES_URL
}
$allowedUserIds = if ([string]::IsNullOrWhiteSpace($env:MEMBERSHIP_PAYMENT_LOADTEST_ALLOWED_USER_IDS)) {
    @(Get-ApprovedUserIds)
} else {
    @($env:MEMBERSHIP_PAYMENT_LOADTEST_ALLOWED_USER_IDS -split ',' | ForEach-Object { $_.Trim() })
}
$expectedAllowedUserIds = @(Get-ApprovedUserIds) | Sort-Object
$actualAllowedUserIds = @($allowedUserIds | Sort-Object)
if (($actualAllowedUserIds -join ',') -ne ($expectedAllowedUserIds -join ',')) {
    throw 'MEMBERSHIP_PAYMENT_LOADTEST_ALLOWED_USER_IDS must match the sixteen approved existing user IDs.'
}
$callbackPid = if ([string]::IsNullOrWhiteSpace($env:SIMULATED_PAYMENT_PID)) {
    'loadtest-merchant'
} else {
    $env:SIMULATED_PAYMENT_PID
}
$callbackKey = if ([string]::IsNullOrWhiteSpace($env:SIMULATED_PAYMENT_CALLBACK_KEY)) {
    'membership-loadtest-callback-key-v1-local'
} else {
    $env:SIMULATED_PAYMENT_CALLBACK_KEY
}
    Test-ApplicationHealth $baseUrl
    Test-MembershipPaymentTables $postgresUrl
    $null = Request-LoadtestTokens $baseUrl $usersPath $negativeTokensPath
    $rows = Read-RequiredUsers $usersPath -RequireTokens
    Save-RabbitSnapshot $rabbitBeforePath
    $currentStage = 'rabbit-single-consumer-preflight'
    & (Join-Path $PSScriptRoot 'Test-MembershipRabbitSingleConsumer.ps1') `
        -SnapshotPath $rabbitBeforePath | Out-Null
    Save-RedisSnapshot $baseUrl $redisBeforePath

$config = [ordered]@{
    runId = $runId
    scenario = $Scenario
    mode = $Mode
    jmx = $Jmx
    host = $HostName
    port = $Port
    protocol = $Protocol
    threads = $Threads
    rampUp = $RampUp
    concurrency = $Concurrency
    usersCsv = 'loadtest/local/loadtest-users.csv'
    callbackKeyConfigured = [bool]$callbackKey
    cleanupRequested = [bool]$Cleanup
}
$config | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDirPath 'run-config.json') -Encoding UTF8
$reproduceCommand = ".\loadtest\scripts\Invoke-MembershipLoadtestScenario.ps1 " +
    "-Scenario '$Scenario' -Jmx '$Jmx' -Mode '$Mode' -Threads $Threads " +
    "-RampUp $RampUp -Concurrency $Concurrency -HostName '$HostName' -Port $Port -Protocol '$Protocol'"
@(
    "`$env:SPRING_PROFILES_ACTIVE = 'loadtest-realtime'",
    $reproduceCommand,
    'Access Token、callback key 与基础设施密码由本机受限配置重新取得，不写入复现命令。'
) | Set-Content -LiteralPath (Join-Path $runDirPath 'reproduce-command.txt') -Encoding UTF8
# 运行产物保留十六个输入账号以便复现白名单选择，但 Access Token 始终使用固定脱敏占位符。
$rows | ForEach-Object {
    [pscustomobject]@{
        userId = [string]$_.userId
        accessToken = '[REDACTED]'
    }
} | Export-Csv -LiteralPath (Join-Path $runDirPath 'loadtest-users-redacted.csv') -NoTypeInformation -Encoding UTF8
foreach ($inputName in @(
    'membership-state-scenarios.csv',
    'membership-entitlement-resolution-cases.csv',
    'membership-long-observation-cases.csv',
    'membership-callback-cases.csv',
    'membership-callback-identity-cases.csv',
    'membership-batch-cases.csv',
    'membership-race-cases.csv',
    'membership-rabbit-cases.csv',
    'membership-marker-stage-cases.csv',
    'membership-rejected-closing-cases.csv',
    'membership-order-concurrency-cases.csv',
    'membership-recovery-cases.csv')) {
    $inputPath = Join-Path $repoRoot "loadtest/input/$inputName"
    if (Test-Path -LiteralPath $inputPath) {
        Copy-Item -LiteralPath $inputPath -Destination (Join-Path $runDirPath $inputName)
    }
}

$stateCasesPath = switch ($Scenario) {
    'membership-entitlement-resolution-matrix' {
        Join-Path $repoRoot 'loadtest/input/membership-entitlement-resolution-cases.csv'
    }
    'membership-long-observation' {
        Join-Path $repoRoot 'loadtest/input/membership-long-observation-cases.csv'
    }
    default {
        Join-Path $repoRoot 'loadtest/input/membership-state-scenarios.csv'
    }
}
$markerCasesPath = if ($Scenario -eq 'membership-rejected-closing-matrix') {
    Join-Path $repoRoot 'loadtest/input/membership-rejected-closing-cases.csv'
} else {
    Join-Path $repoRoot 'loadtest/input/membership-marker-stage-cases.csv'
}
$raceCasesPath = if ($Scenario -eq 'membership-callback-identity') {
    Join-Path $repoRoot 'loadtest/input/membership-callback-identity-cases.csv'
} else {
    Join-Path $repoRoot 'loadtest/input/membership-race-cases.csv'
}
$raceCaseCount = if ($Scenario -eq 'membership-callback-identity') { 4 } else { 8 }

$jmeterArgs = @(
    '-n', '-t', $jmxPath, '-l', $jtlPath, '-j', $logPath, '-e', '-o', $reportPath,
    "-JRUN_ID=$runId", "-JMODE=$Mode", "-JTHREADS=$Threads", "-JRAMP_UP=$RampUp",
    "-JRACE_CONCURRENCY=$Concurrency",
    "-JHOST=$HostName", "-JPORT=$Port", "-JPROTOCOL=$Protocol",
    "-JUSERS_CSV=$usersPath", "-JSCENARIO_ORDERS_CSV=$scenarioCsvPath",
    "-JAUTH_NEGATIVE_TOKENS_FILE=$negativeTokensPath",
    "-JAUTH_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-auth-boundary.groovy')",
    "-JSTATE_SCENARIOS_CSV=$stateCasesPath",
    "-JSTATE_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-state-machine-realtime.groovy')",
    "-JORDER_CONCURRENCY_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-order-concurrency.groovy')",
    "-JTRANSPORT_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-callback-transport.groovy')",
    "-JRACE_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-callback-race-idempotency.groovy')",
    "-JRABBIT_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-rabbit-state-timing.groovy')",
    "-JMARKER_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-marker-stage-matrix.groovy')",
    "-JBATCH_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-persistence-batch.groovy')",
    "-JRECOVERY_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-recovery-terminal-cleanup.groovy')",
    "-JCALLBACK_CASES_CSV=$(Join-Path $repoRoot 'loadtest/input/membership-callback-cases.csv')",
    "-JRACE_CASES_CSV=$raceCasesPath", "-JRACE_CASE_COUNT=$raceCaseCount",
    "-JRABBIT_CASES_CSV=$(Join-Path $repoRoot 'loadtest/input/membership-rabbit-cases.csv')",
    "-JMARKER_CASES_CSV=$markerCasesPath",
    "-JORDER_CONCURRENCY_CASES_CSV=$(Join-Path $repoRoot 'loadtest/input/membership-order-concurrency-cases.csv')",
    "-JRECOVERY_CASES_CSV=$(Join-Path $repoRoot 'loadtest/input/membership-recovery-cases.csv')",
    "-JBATCH_CASES_CSV=$(Join-Path $repoRoot 'loadtest/input/membership-batch-cases.csv')",
    '-Jjmeter.save.saveservice.output_format=csv', '-Jjmeter.save.saveservice.print_field_names=true'
)
if ($callbackKey) { $jmeterArgs += "-JCALLBACK_KEY=$callbackKey" }
if ($callbackPid) { $jmeterArgs += "-JCALLBACK_PID=$callbackPid" }
if (-not [string]::IsNullOrWhiteSpace($OrderId)) { $jmeterArgs += "-JORDER_ID=$OrderId" }
if (-not [string]::IsNullOrWhiteSpace($Money)) { $jmeterArgs += "-JMONEY=$Money" }

    $currentStage = 'jmeter'
    & jmeter @jmeterArgs
    $jmeterExit = $LASTEXITCODE
    if ($jmeterExit -ne 0) { throw "JMeter failed with exit code $jmeterExit. Check $logPath" }

    if (Test-Path -LiteralPath $scenarioCsvPath) {
    $timeRows = @(Import-Csv -LiteralPath $scenarioCsvPath | Where-Object {
        $_.PSObject.Properties.Name -contains 'target_callback_at'
    } | Select-Object run_id, order_id, scenario, scenario_group,
        target_callback_at, actual_callback_at, callback_drift_millis)
    if ($timeRows.Count -gt 0) {
        $timeRows | Export-Csv -LiteralPath $timeDeviationPath -NoTypeInformation -Encoding UTF8
    }
}

    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'summarize-membership-jmeter.ps1')) {
    & (Join-Path $PSScriptRoot 'summarize-membership-jmeter.ps1') -JtlPath $jtlPath -OutputPath $summaryPath
    if ($LASTEXITCODE -ne 0) { throw 'JMeter summary generation failed.' }
    if (-not (Test-Path -LiteralPath $summaryPath)) { throw 'JMeter summary was not generated.' }
    $summaryRows = @(Import-Csv -LiteralPath $summaryPath)
    $failedAssertions = @($summaryRows | Where-Object { [int]$_.failures -gt 0 })
    if ($failedAssertions.Count -gt 0) {
        $failedLabels = ($failedAssertions | ForEach-Object { $_.label }) -join ', '
        throw "JMeter business assertions failed for: $failedLabels. Check $jtlPath"
    }
}
    if (-not (Test-Path -LiteralPath $scenarioCsvPath)) {
        throw 'Scenario did not generate the required scenario-orders.csv evidence.'
    }
    $expectedEvidenceRows = Get-ExpectedEvidenceRows $Scenario
    if ($expectedEvidenceRows -gt 0) {
        $actualEvidenceRows = @(Import-Csv -LiteralPath $scenarioCsvPath).Count
        if ($actualEvidenceRows -ne $expectedEvidenceRows) {
            throw "Scenario $Scenario requires exactly $expectedEvidenceRows evidence rows; received $actualEvidenceRows."
        }
    }
    $currentStage = 'postgresql-verification'
    if (Test-Path -LiteralPath $scenarioCsvPath) {
    $sqlFile = switch ($Scenario) {
        'membership-auth-boundary' {
            'verify-membership-auth-boundary.sql'
        }
        'membership-callback-race-idempotency' {
            'verify-membership-callback-race.sql'
        }
        'membership-callback-identity' {
            'verify-membership-callback-race.sql'
        }
        'membership-callback-transport' {
            'verify-membership-callback-transport.sql'
        }
        'membership-persistence-batch' {
            'verify-membership-persistence-batch.sql'
        }
        'membership-rabbit-state-timing' {
            'verify-membership-rabbit-state-timing.sql'
        }
        'membership-marker-stage-matrix' {
            'verify-membership-marker-stage-matrix.sql'
        }
        'membership-rejected-closing-matrix' {
            'verify-membership-marker-stage-matrix.sql'
        }
        'membership-order-concurrency' {
            'verify-membership-order-concurrency.sql'
        }
        'membership-long-observation' {
            'verify-membership-long-observation.sql'
        }
        'membership-entitlement-resolution-matrix' {
            'verify-membership-entitlement-resolution.sql'
        }
        'membership-recovery-terminal-cleanup' {
            'verify-membership-recovery-terminal-cleanup.sql'
        }
        default {
            'verify-membership-payment.sql'
        }
    }
    $sqlPath = Join-Path $repoRoot "loadtest/sql/$sqlFile"
    # psql 的 \copy 元命令不会展开 :variable 文件名；这里仅把 Runner 自己生成的绝对路径写入运行副本，避免把客户端输入拼进 SQL。
    $verificationSql = Get-Content -Raw -LiteralPath $sqlPath
    $copyPath = ($scenarioCsvPath -replace '\\', '/') -replace "'", "''"
    $copyDirective = [regex]::Match(
        $verificationSql,
        "(?m)^\\copy ([a-z_]+) FROM :'scenario_csv' CSV HEADER\r?$")
    if (-not $copyDirective.Success) {
        throw "Verification SQL does not contain one supported scenario CSV import: $sqlFile"
    }
    $replacement = "\copy $($copyDirective.Groups[1].Value) FROM '$copyPath' CSV HEADER"
    $verificationSql = $verificationSql.Remove(
        $copyDirective.Index,
        $copyDirective.Length).Insert($copyDirective.Index, $replacement)
    $verificationSqlPath = Join-Path $runDirPath 'verify-membership-payment-run.sql'
    $verificationSql | Set-Content -LiteralPath $verificationSqlPath -Encoding UTF8
    & psql -w $postgresUrl -v ON_ERROR_STOP=1 -f $verificationSqlPath |
        Tee-Object -FilePath $sqlOutputPath
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL verification failed.' }
    if ((Get-Content -Raw -LiteralPath $sqlOutputPath) -notmatch '(?i)\bPASS\b') {
        throw 'PostgreSQL verification returned a non-PASS verdict.'
    }
}
    if ($Cleanup) {
        $currentStage = 'cleanup'
    if ($Mode -ne 'loadtest-realtime' -or $activeProfiles -notcontains 'loadtest-realtime') {
        throw 'Cleanup is only allowed for an explicitly enabled loadtest Profile.'
    }
    if (-not (Test-Path -LiteralPath $scenarioCsvPath)) { throw 'Cleanup requires the exact scenario order CSV.' }
    $cleanupRows = @(Import-Csv -LiteralPath $scenarioCsvPath |
        Where-Object {
            $_.PSObject.Properties.Name -contains 'idempotency_key' `
                -and -not [string]::IsNullOrWhiteSpace([string]$_.idempotency_key)
        } |
        ForEach-Object { [pscustomobject]@{ idempotency_key = [string]$_.idempotency_key } } |
        Sort-Object idempotency_key -Unique)
    if ($cleanupRows.Count -eq 0) {
        throw 'Cleanup scenario evidence does not contain any idempotency_key values.'
    }
    $cleanupInputPath = Join-Path $runDirPath 'cleanup-order-ids.csv'
    $cleanupRows | Export-Csv -LiteralPath $cleanupInputPath -NoTypeInformation -Encoding UTF8
    $cleanupSqlTemplate = Join-Path $PSScriptRoot 'cleanup-membership-payment.sql'
    $cleanupSqlText = Get-Content -Raw -LiteralPath $cleanupSqlTemplate
    $cleanupCopyPath = ($cleanupInputPath -replace '\\', '/') -replace "'", "''"
    $cleanupSqlText = $cleanupSqlText.Replace(
        "\copy cleanup_orders FROM :'scenario_csv' CSV HEADER",
        "\copy cleanup_orders FROM '$cleanupCopyPath' CSV HEADER")
    $cleanupSqlPath = Join-Path $runDirPath 'cleanup-membership-payment-run.sql'
    $cleanupSqlText | Set-Content -LiteralPath $cleanupSqlPath -Encoding UTF8
    & psql -w $postgresUrl -v ON_ERROR_STOP=1 -f $cleanupSqlPath | Out-File -LiteralPath (Join-Path $runDirPath 'cleanup-output.txt') -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw 'Explicit membership loadtest cleanup failed.' }
    $cleanupPerformed = $true
}
    $currentStage = 'bounded-settling'
    if ($SettleSeconds -gt 0) { Start-Sleep -Seconds $SettleSeconds }
    $currentStage = 'rabbitmq-verification'
    Save-RabbitSnapshot $rabbitAfterPath
    Assert-RabbitTopologyAndBaseline `
        $rabbitBeforePath `
        $rabbitAfterPath `
        $Scenario `
        $rabbitVerificationPath
    $currentStage = 'redis-verification'
    Wait-RedisQueuesAtOrBelowBaseline $baseUrl $redisBeforePath 120
    Save-RedisSnapshot $baseUrl $redisAfterPath
    Assert-RedisBaselineAndTerminalArtifacts `
        $baseUrl `
        $redisBeforePath `
        $redisAfterPath `
        $scenarioCsvPath `
        $redisVerificationPath

    $verdict = [ordered]@{
        verdict = 'PASS'
        scenario = $Scenario
        mode = $Mode
        jmeterExitCode = $jmeterExit
        sqlVerified = (Test-Path -LiteralPath $sqlOutputPath)
        redisVerified = (Test-Path -LiteralPath $redisVerificationPath)
        rabbitVerified = (Test-Path -LiteralPath $rabbitVerificationPath)
        cleanupPerformed = $cleanupPerformed
        outputDirectory = $runDirPath
    }
    $verdict | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $verdictPath -Encoding UTF8
    Write-Host "Scenario PASS: $Scenario"
    Write-Host "Output directory: $runDirPath"
} catch {
    $failure = $_
    # 失败产物只记录阶段、异常类型和受控消息，不复制命令行，因此不会泄露 Token 或 callback key。
    [ordered]@{
        verdict = 'FAIL'
        scenario = $Scenario
        mode = $Mode
        failedStage = $currentStage
        errorType = $failure.Exception.GetType().FullName
        error = $failure.Exception.Message
        cleanupPerformed = $cleanupPerformed
        outputDirectory = $runDirPath
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $verdictPath -Encoding UTF8
    Write-Warning "Scenario FAIL: $Scenario at stage $currentStage."
    throw
}
