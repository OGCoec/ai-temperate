param(
    [ValidateRange(1, 65535)]
    [int] $Port = 6655,
    [string] $HostAddress = '127.0.0.1',
    [string] $JavaExecutable = '',
    [string] $RedisContainer = 'redis7',
    [string] $RabbitContainer = 'rabbitmq1',
    [string] $RunId = '',
    [ValidateSet(256)]
    [int] $PostgresPoolMaximumSize = 256,
    [ValidateSet(8)]
    [int] $PostgresPoolMinimumIdle = 8,
    [ValidateSet(64)]
    [int] $RedisWriteBatchSize = 64,
    [ValidateSet(6)]
    [int] $RedisWriteLaneCount = 6,
    [ValidateSet(384)]
    [int] $RedisWriteMaximumInflight = 384,
    [switch] $EnableMillisecondBoundary
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PostgresPoolMinimumIdle -gt $PostgresPoolMaximumSize) {
    throw 'PostgreSQL pool minimum idle cannot exceed maximum pool size.'
}

if ($RunId -notmatch '^[A-Za-z0-9_-]{1,128}$' -and
    -not [string]::IsNullOrWhiteSpace($RunId)) {
    throw 'RunId may contain only letters, digits, underscore and hyphen, with at most 128 characters.'
}
if ($EnableMillisecondBoundary -and [string]::IsNullOrWhiteSpace($RunId)) {
    throw 'Millisecond-boundary timing requires an explicit RunId shared with the suite.'
}

function Get-ContainerArgumentSecret(
    [string] $Container,
    [string] $ArgumentName) {
    $inspection = @(& docker inspect $Container 2>$null)
    if ($LASTEXITCODE -ne 0 -or $inspection.Count -eq 0) {
        throw "Container inspection failed: $Container"
    }
    $containerState = ($inspection -join "`n") | ConvertFrom-Json
    $arguments = @($containerState[0].Config.Cmd)
    $argumentIndex = [Array]::IndexOf($arguments, $ArgumentName)
    if ($argumentIndex -lt 0 -or $argumentIndex + 1 -ge $arguments.Count) {
        throw "Container argument is missing: $Container/$ArgumentName"
    }
    return [string]$arguments[$argumentIndex + 1]
}

function Get-ContainerEnvironmentSecret(
    [string] $Container,
    [string] $VariableName) {
    $inspection = @(& docker inspect $Container 2>$null)
    if ($LASTEXITCODE -ne 0 -or $inspection.Count -eq 0) {
        throw "Container inspection failed: $Container"
    }
    $containerState = ($inspection -join "`n") | ConvertFrom-Json
    $prefix = "$VariableName="
    $entry = @($containerState[0].Config.Env) |
        Where-Object { $_ -like "$prefix*" } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace([string]$entry)) {
        throw "Container environment variable is missing: $Container/$VariableName"
    }
    return ([string]$entry).Substring($prefix.Length)
}

$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jarPath = (Resolve-Path (Join-Path $workspace `
    'ai-temperate-web\target\ai-temperate-web-0.0.1-SNAPSHOT.jar')).Path
$jarSha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($jarSha256 -ne 'b3c924c4abf49266957b9f93076fa2268e5c1e7e447899a1411eff16375ac597') {
    throw "TEST_INVALID_ARTIFACT: fixed JAR SHA-256 mismatch: $jarSha256"
}

# 真实时间测试只允许一个应用实例消费会员队列；启动前拒绝任何已有业务 JVM。
$existingApplications = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object {
        $_.CommandLine -match 'AiTemperateApplication' -or
        $_.CommandLine -match 'ai-temperate-web-0\.0\.1-SNAPSHOT\.jar'
    })
if ($existingApplications.Count -ne 0) {
    throw 'Another AiTemperateApplication is already running.'
}

$occupiedLoopbackPort = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object {
        $_.LocalPort -eq $Port -and
        ($_.LocalAddress -eq '127.0.0.1' -or $_.LocalAddress -eq '0.0.0.0' -or
            $_.LocalAddress -eq '::')
    })
if ($occupiedLoopbackPort.Count -ne 0) {
    throw "The requested loopback port is already occupied: $Port"
}

$env:REDIS_PASSWORD = Get-ContainerArgumentSecret $RedisContainer '--requirepass'
$env:RABBITMQ_USERNAME = Get-ContainerEnvironmentSecret `
    $RabbitContainer 'RABBITMQ_DEFAULT_USER'
$env:RABBITMQ_PASSWORD = Get-ContainerEnvironmentSecret `
    $RabbitContainer 'RABBITMQ_DEFAULT_PASS'
$env:APP_ENV = 'LOCAL'
$env:SPRING_PROFILES_ACTIVE = 'loadtest-realtime'
$env:SERVER_ADDRESS = $HostAddress
$env:SERVER_PORT = [string]$Port
$env:MEMBERSHIP_PAYMENT_ENABLED = 'true'
$env:MEMBERSHIP_PAYMENT_CHECKOUT_ENABLED = 'true'
$env:SIMULATED_PAYMENT_ENABLED = 'true'
$env:BAR_PAYMENT_ENABLED = 'false'
$env:MEMBERSHIP_PAYMENT_LOADTEST_ENABLED = 'true'
$env:MEMBERSHIP_PAYMENT_TIMING_RUN_ID = if ([string]::IsNullOrWhiteSpace($RunId)) {
    'unavailable'
} else {
    $RunId
}
$env:MEMBERSHIP_PAYMENT_TIMING_INCLUDE_PUBLIC_ORDER_ID = if ($EnableMillisecondBoundary) {
    'true'
} else {
    'false'
}
# 第二轮关闭旧的全量与抽样入口，只强制记录两个 HTTP 主链路；其他操作仍按慢请求、失败和 NACK 规则记录。
$env:MEMBERSHIP_PAYMENT_TIMING_DETAIL_LOG_ENABLED = 'false'
$env:MEMBERSHIP_PAYMENT_TIMING_SAMPLE_RATE = '0'
$env:MEMBERSHIP_PAYMENT_TIMING_FORCE_LOG_OPERATIONS =
        'ORDER_CREATE,PAYMENT_ATTEMPT'
$env:MEMBERSHIP_PAYMENT_BOUNDARY_LOADTEST_ENABLED = if ($EnableMillisecondBoundary) {
    'true'
} else {
    'false'
}
$env:MEMBERSHIP_PAYMENT_DEFAULT_PROVIDER = 'LOCAL_SIMULATOR'
$env:POSTGRES_POOL_MAXIMUM_SIZE = [string]$PostgresPoolMaximumSize
$env:POSTGRES_POOL_MINIMUM_IDLE = [string]$PostgresPoolMinimumIdle
$env:MEMBERSHIP_LOADTEST_TOMCAT_ACCEPT_COUNT = '256'
$env:MEMBERSHIP_LOADTEST_TOMCAT_MAX_CONNECTIONS = '256'
$env:MEMBERSHIP_LOADTEST_TOMCAT_MAX_THREADS = '256'
$env:MEMBERSHIP_PAYMENT_REDIS_WRITE_BATCH_SIZE = [string]$RedisWriteBatchSize
$env:MEMBERSHIP_PAYMENT_REDIS_WRITE_LANE_COUNT = [string]$RedisWriteLaneCount
$env:MEMBERSHIP_PAYMENT_REDIS_WRITE_MAXIMUM_INFLIGHT = [string]$RedisWriteMaximumInflight

$runtimeDirectory = Join-Path $workspace 'loadtest-output\runtime'
$timingDirectory = Join-Path $workspace 'logs'
$env:MEMBERSHIP_PAYMENT_TIMING_LOG_PATH = Join-Path $workspace 'logs\membership-payment-state-machine.log'
$env:MEMBERSHIP_ORDER_CREATE_HTTP_EVIDENCE_ENABLED = if ($EnableMillisecondBoundary) {
    'true'
} else {
    'false'
}
$env:MEMBERSHIP_ORDER_CREATE_HTTP_LOG_PATH = Join-Path $workspace `
    'logs\membership-order-create-http-events.log'
New-Item -ItemType Directory -Force -Path $runtimeDirectory, $timingDirectory | Out-Null
if ($EnableMillisecondBoundary) {
    foreach ($formalLogPath in @(
            $env:MEMBERSHIP_PAYMENT_TIMING_LOG_PATH,
            $env:MEMBERSHIP_ORDER_CREATE_HTTP_LOG_PATH)) {
        if ((Test-Path -LiteralPath $formalLogPath -PathType Leaf) -and
                (Get-Item -LiteralPath $formalLogPath).Length -gt 0L) {
            throw "Formal evidence log must be archived and removed before application start: $formalLogPath"
        }
    }
}
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdoutPath = Join-Path $runtimeDirectory "loadtest-realtime-$Port-$timestamp.out.log"
$stderrPath = Join-Path $runtimeDirectory "loadtest-realtime-$Port-$timestamp.err.log"

$resolvedJavaExecutable = if (-not [string]::IsNullOrWhiteSpace($JavaExecutable)) {
    (Resolve-Path -LiteralPath $JavaExecutable).Path
} elseif (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) `
        -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    (Join-Path $env:JAVA_HOME 'bin\java.exe')
} elseif (Test-Path -LiteralPath 'D:\jdk21\bin\java.exe') {
    'D:\jdk21\bin\java.exe'
} else {
    (Get-Command java -ErrorAction Stop).Source
}

$application = Start-Process `
    -FilePath $resolvedJavaExecutable `
    -ArgumentList @('-jar', $jarPath) `
    -WorkingDirectory $workspace `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath `
    -WindowStyle Hidden `
    -PassThru

# 输出只包含运行定位信息；容器密码和应用密钥仅存在于新进程环境中。
[ordered]@{
    pid = $application.Id
    host = $HostAddress
    port = $Port
    runId = $env:MEMBERSHIP_PAYMENT_TIMING_RUN_ID
    jarSha256 = $jarSha256
    postgresPoolMaximumSize = $PostgresPoolMaximumSize
    postgresPoolMinimumIdle = $PostgresPoolMinimumIdle
    tomcatAcceptCount = 256
    tomcatMaxConnections = 256
    tomcatMaxThreads = 256
    redisWriteBatchSize = $RedisWriteBatchSize
    redisWriteLaneCount = $RedisWriteLaneCount
    redisWriteMaximumInflight = $RedisWriteMaximumInflight
    detailLogEnabled = $env:MEMBERSHIP_PAYMENT_TIMING_DETAIL_LOG_ENABLED
    sampleRate = $env:MEMBERSHIP_PAYMENT_TIMING_SAMPLE_RATE
    forceLogOperations = $env:MEMBERSHIP_PAYMENT_TIMING_FORCE_LOG_OPERATIONS
    timingLogPath = $env:MEMBERSHIP_PAYMENT_TIMING_LOG_PATH
    orderCreateHttpEvidenceEnabled = $env:MEMBERSHIP_ORDER_CREATE_HTTP_EVIDENCE_ENABLED
    orderCreateHttpLogPath = $env:MEMBERSHIP_ORDER_CREATE_HTTP_LOG_PATH
    stdoutPath = $stdoutPath
    stderrPath = $stderrPath
} | ConvertTo-Json -Compress
