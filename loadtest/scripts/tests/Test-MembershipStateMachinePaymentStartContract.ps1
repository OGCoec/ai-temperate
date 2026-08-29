$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$groovyPath = Join-Path $PSScriptRoot '..\jmeter\membership-state-machine-realtime.groovy'
$sqlPath = Join-Path $PSScriptRoot '..\..\sql\verify-membership-payment.sql'
$longObservationSqlPath =
        Join-Path $PSScriptRoot '..\..\sql\verify-membership-long-observation.sql'

foreach ($path in @($groovyPath, $sqlPath, $longObservationSqlPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required state-machine verification source is missing: $path"
    }
}

$groovy = Get-Content -Raw -LiteralPath $groovyPath
$sql = Get-Content -Raw -LiteralPath $sqlPath
$longObservationSql = Get-Content -Raw -LiteralPath $longObservationSqlPath

# 取消先于支付发起以及取消/支付发起并发都允许 payment_started_at 为空；
# 证据必须携带场景事实，SQL 禁止通过 X-01 等场景名猜测该约束。
foreach ($contract in @(
    'payment_started_required',
    "Boolean.parseBoolean(vars.get('startPayment'))"
)) {
    if (-not $groovy.Contains($contract)) {
        throw "State-machine evidence is missing payment-start contract: $contract"
    }
}

foreach ($contract in @(
    'payment_started_required BOOLEAN NOT NULL',
    'scoped.payment_started_required'
)) {
    if (-not $sql.Contains($contract)) {
        throw "PostgreSQL verification is missing payment-start contract: $contract"
    }
}

if ($sql.Contains("scoped.scenario <> 'X-01'")) {
    throw 'PostgreSQL verification must not hard-code scenario names for payment-start expectations.'
}

# W08 复用同一份状态机场景证据；临时表必须保持与 CSV 完全相同的列契约，
# 否则 psql 会在 COPY 阶段失败，造成业务结果正常却被误判为 PostgreSQL 验证失败。
if (-not $longObservationSql.Contains('payment_started_required BOOLEAN NOT NULL')) {
    throw 'Long-observation PostgreSQL verification is missing payment-start contract.'
}

Write-Output 'PASS'
