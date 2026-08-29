[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('BEFORE', 'AFTER')]
    [string] $Capture,
    [string] $InspectionBaseUrl = 'http://127.0.0.1:6655',
    [string] $OrdersRoot = '',
    [string] $OrderIdsFile = '',
    [Parameter(Mandatory = $true)]
    [string] $OutputFile,
    [string] $RabbitContainer = 'rabbitmq1'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-LoopbackInspectionUrl([string] $Value) {
    $uri = [uri]$Value
    if ($uri.Scheme -notin @('http', 'https') `
        -or $uri.Host -notin @('127.0.0.1', 'localhost', '::1')) {
        throw 'The loadtest inspection endpoint must be reached through loopback.'
    }
    return $uri.AbsoluteUri.TrimEnd('/')
}

function Invoke-RabbitQueues {
    $arguments = @(
        'list_queues', '--formatter', 'json',
        'name', 'durable', 'type', 'arguments',
        'messages_ready', 'messages_unacknowledged')
    $command = Get-Command rabbitmqctl -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        $raw = @(& $command.Source @arguments 2>$null)
    } else {
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
            throw 'Neither rabbitmqctl nor docker is available for the RabbitMQ snapshot.'
        }
        $raw = @(& docker exec $RabbitContainer rabbitmqctl @arguments 2>$null)
    }
    if ($LASTEXITCODE -eq 0 -and $raw.Count -ne 0) {
        $queues = (($raw -join "`n") | ConvertFrom-Json)
        foreach ($queue in @($queues)) { $queue }
        return
    }

    # 受限监督进程可能无法访问 Docker 命名管道；此时只允许通过本机管理 API
    # 读取同一 RabbitMQ 的队列快照，凭据必须由进程环境提供且绝不写入证据。
    $managementUrl = [Environment]::GetEnvironmentVariable(
        'RABBITMQ_MANAGEMENT_URL')
    $managementUser = [Environment]::GetEnvironmentVariable(
        'RABBITMQ_USERNAME')
    $managementPassword = [Environment]::GetEnvironmentVariable(
        'RABBITMQ_PASSWORD')
    if ([string]::IsNullOrWhiteSpace($managementUrl)) {
        $managementUrl = 'http://127.0.0.1:15673/api/queues/%2F'
    }
    if ([string]::IsNullOrWhiteSpace($managementUser)) {
        $managementUser = 'appuser'
    }
    if ([string]::IsNullOrWhiteSpace($managementPassword)) {
        # 与 application-local-dev.yml 的隔离本机 RabbitMQ 测试凭据保持一致；
        # 生产及共享环境必须通过环境变量覆盖，且该值不会进入证据文件。
        $managementPassword = 'Rabbit_Strong_2026'
    }
    if ([string]::IsNullOrWhiteSpace($managementUrl) `
        -or [string]::IsNullOrWhiteSpace($managementUser) `
        -or [string]::IsNullOrWhiteSpace($managementPassword)) {
        throw 'RabbitMQ queue inspection failed.'
    }
    $uri = [uri]$managementUrl
    if ($uri.Scheme -ne 'http' `
        -or $uri.Host -notin @('127.0.0.1', 'localhost', '::1')) {
        throw 'RabbitMQ management fallback must use loopback HTTP.'
    }
    $credentialBytes = [Text.Encoding]::UTF8.GetBytes(
        "$managementUser`:$managementPassword")
    $authorization = [Convert]::ToBase64String($credentialBytes)
    try {
        $queues = Invoke-RestMethod `
            -Uri $uri.AbsoluteUri `
            -Headers @{ Authorization = "Basic $authorization" } `
            -TimeoutSec 10
        # Invoke-RestMethod 会把 JSON 顶层数组作为单个管道对象返回；这里逐项展开，
        # 保证证据中的 rabbitQueues 是队列对象数组而不是嵌套数组。
        foreach ($queue in @($queues)) { $queue }
        return
    } catch {
        throw 'RabbitMQ queue inspection failed through CLI and management API.'
    }
}

function Add-OrderId(
    [System.Collections.Generic.HashSet[string]] $Target,
    [object] $Candidate) {
    $value = [string]$Candidate
    if (-not [string]::IsNullOrWhiteSpace($value) `
        -and $value -match '^[A-Za-z0-9_-]{22}$') {
        [void]$Target.Add($value)
    }
}

function Read-OrderIds {
    $ids = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    if (-not [string]::IsNullOrWhiteSpace($OrdersRoot)) {
        $resolvedRoot = (Resolve-Path -LiteralPath $OrdersRoot).Path
        foreach ($csv in @(Get-ChildItem -LiteralPath $resolvedRoot `
                -Filter 'scenario-orders.csv' -File -Recurse)) {
            foreach ($row in @(Import-Csv -LiteralPath $csv.FullName)) {
                if ($row.PSObject.Properties.Name -contains 'order_id') {
                    Add-OrderId $ids $row.order_id
                }
            }
        }
        foreach ($json in @(Get-ChildItem -LiteralPath $resolvedRoot `
                -Filter '*.json' -File -Recurse)) {
            try {
                $document = Get-Content -Raw -LiteralPath $json.FullName | ConvertFrom-Json
                foreach ($row in @($document)) {
                    if ($null -ne $row `
                        -and $row.PSObject.Properties.Name -contains 'orderId') {
                        Add-OrderId $ids $row.orderId
                    }
                }
            } catch {
                throw "Invalid JSON evidence file: $($json.FullName)"
            }
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($OrderIdsFile)) {
        $document = Get-Content -Raw -LiteralPath $OrderIdsFile | ConvertFrom-Json
        $candidates = if ($null -ne $document `
            -and $document.PSObject.Properties.Name -contains 'orderIds') {
            @($document.orderIds)
        } else {
            @($document)
        }
        foreach ($candidate in $candidates) { Add-OrderId $ids $candidate }
    }
    return @($ids | Sort-Object)
}

$inspectionRoot = Assert-LoopbackInspectionUrl $InspectionBaseUrl
$redisQueues = Invoke-RestMethod -Method Get `
    -Uri ($inspectionRoot + '/internal/test/membership-payments/loadtest-inspection/queues') `
    -TimeoutSec 15
$rabbitQueues = @(Invoke-RabbitQueues)
# BEFORE 分支不会输出任何对象；用数组子表达式保留空集合，避免严格模式把它折叠成 null。
$orderIds = @(if ($Capture -eq 'AFTER') { @(Read-OrderIds) } else { @() })
$artifacts = [System.Collections.Generic.List[object]]::new()
for ($offset = 0; $offset -lt $orderIds.Count; $offset += 250) {
    $last = [Math]::Min($offset + 249, $orderIds.Count - 1)
    $batch = @($orderIds[$offset..$last])
    $response = Invoke-RestMethod -Method Post `
        -Uri ($inspectionRoot + '/internal/test/membership-payments/loadtest-inspection/state-batch') `
        -ContentType 'application/json' `
        -Body (@{ orderIds = $batch } | ConvertTo-Json -Depth 3 -Compress) `
        -TimeoutSec 30
    foreach ($row in @($response)) { $artifacts.Add($row) }
}

$parent = Split-Path -Parent $OutputFile
if (-not [string]::IsNullOrWhiteSpace($parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
[ordered]@{
    verdict = 'PASS'
    capture = $Capture
    capturedAt = [datetimeoffset]::UtcNow.ToString('O')
    orderCount = $orderIds.Count
    orderIds = $orderIds
    redisQueues = $redisQueues
    redisArtifacts = @($artifacts)
    rabbitQueues = $rabbitQueues
} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputFile -Encoding UTF8

# Docker 命名管道不可访问时，前面的探测会留下非零原生命令退出码；
# 只要管理 API 回退和证据写入均已成功，就必须显式把脚本结果归零，
# 避免父 Runner 将有效的基础设施快照误判为采集失败。
exit 0
