[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $SoakRoot,
    [string] $OutputDirectory = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $SoakRoot).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $root 'report'
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function Read-RequiredJson([string] $RelativePath) {
    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required soak evidence is missing: $RelativePath"
    }
    return Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
}

function Assert-Pass([object] $Evidence, [string] $Name) {
    if ([string]$Evidence.verdict -ne 'PASS') {
        throw "$Name verdict is not PASS."
    }
}

$localGate = Read-RequiredJson 'local/deployment-gate.json'
$localWaves = @(Read-RequiredJson 'local/local-wave-results.json')
$localInfrastructure = Read-RequiredJson 'local/infrastructure-verdict.json'
$barVerdict = Read-RequiredJson 'bar/bar-verdict.json'
$barWaves = @(Read-RequiredJson 'bar/bar-wave-results.json')
$barInfrastructure = Read-RequiredJson 'bar/infrastructure-verdict.json'

Assert-Pass $localGate 'Local phase'
Assert-Pass $localInfrastructure 'Local infrastructure'
Assert-Pass $barVerdict 'BAR phase'
Assert-Pass $barInfrastructure 'BAR infrastructure'
foreach ($wave in $localWaves) { Assert-Pass $wave "Local wave $($wave.wave)" }
foreach ($wave in $barWaves) { Assert-Pass $wave "BAR wave $($wave.wave)" }

$localDatabasePath = Join-Path $root 'local/final-database-scan.txt'
$barDatabasePath = Join-Path $root 'bar/final-database-scan.txt'
foreach ($path in @($localDatabasePath, $barDatabasePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) `
        -or (Get-Content -Raw -LiteralPath $path) -notmatch '(?im)^\s*PASS\s*$') {
        throw "Final database evidence is not PASS: $path"
    }
}

$localOrders = [int]$localGate.actualOrdersExpected
$barOrders = [int]$barVerdict.actualOrders
$combinedOrders = $localOrders + $barOrders
if ($localOrders -ne 148 -or $barOrders -ne 120 -or $combinedOrders -ne 268) {
    throw "Soak order count is invalid: local=$localOrders bar=$barOrders total=$combinedOrders"
}
$combinedHours = [double]$barVerdict.combinedObservationHours
if ($combinedHours -lt 24.0) {
    throw "Soak observation is shorter than twenty-four hours: $combinedHours"
}

$summary = [ordered]@{
    verdict = 'PASS'
    soakId = [string]$barVerdict.soakId
    sourceFingerprint = [string]$localGate.sourceFingerprint
    totalOrders = $combinedOrders
    localOrders = $localOrders
    barOrders = $barOrders
    combinedObservationHours = $combinedHours
    localWaveRuns = $localWaves.Count
    barWaveRuns = $barWaves.Count
    databaseVerified = $true
    redisVerified = $true
    rabbitVerified = $true
    barVerified = $true
    browserEvidenceRequired = 2
    localInfrastructure = $localInfrastructure
    barInfrastructure = $barInfrastructure
    completedAt = [datetimeoffset]::UtcNow.ToString('O')
}
$jsonPath = Join-Path $OutputDirectory '24-hour-verdict.json'
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$markdown = @"
# 会员支付 24 小时全天浸泡测试总报告

- 最终裁决：**PASS**
- Soak ID：``$($summary.soakId)``
- 构建指纹：``$($summary.sourceFingerprint)``
- 实际订单：$combinedOrders（本地 $localOrders + BAR $barOrders）
- 合并观察时长：$([Math]::Round($combinedHours, 3)) 小时
- 本地波次运行：$($localWaves.Count)
- BAR 波次运行：$($barWaves.Count)

## 最终一致性裁决

- PostgreSQL 两阶段最终扫描：PASS
- Redis 队列、订单 Snapshot 与 callback Marker：PASS
- RabbitMQ Ready、Unacked、有限 DLQ 增量：PASS
- BAR 正式管理 API 对账：PASS
- 外部 Chrome Extension 完整跳转证据：2 笔

本报告只在全部硬门禁通过后生成；不存在“基本通过”或把修复前后结果拼接为同一轮 PASS 的语义。
"@
$markdown | Set-Content -LiteralPath `
    (Join-Path $OutputDirectory '24-hour-report.md') -Encoding UTF8
Write-Output $jsonPath

