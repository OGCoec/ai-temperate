$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$sqlPath = Join-Path $PSScriptRoot '..\..\sql\verify-membership-marker-stage-matrix.sql'
$longObservationSqlPath =
        Join-Path $PSScriptRoot '..\..\sql\verify-membership-long-observation.sql'
foreach ($path in @($sqlPath, $longObservationSqlPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Membership PostgreSQL verifier is missing: $path"
    }
}

$sql = Get-Content -Raw -LiteralPath $sqlPath
$longObservationSql = Get-Content -Raw -LiteralPath $longObservationSqlPath

# PENDING 内直接 APPLIED 的订单从未进入 CLOSING，closing_deadline_at 保持 NULL；
# 只有 CLOSING 阶段或最终 CLOSED 的案例才具备硬关闭截止合同。
$required = @(
    "test.phase = 'CLOSING' OR test.expected_status = 'CLOSED'",
    "phase = 'CLOSING' OR expected_status = 'CLOSED'"
)
foreach ($contract in $required) {
    if (-not $sql.Contains($contract)) {
        throw "Marker-stage deadline contract is missing: $contract"
    }
}

# 长观察矩阵同样包含 PENDING 内直接 PAID 的案例；只有进入 CLOSING，
# 或最终 CLOSED 的订单，才必须持有 expires_at + 5 分钟的硬关闭截止时间。
if (-not $longObservationSql.Contains(
        "test.scenario_group = 'CLOSING' OR test.expected_status = 'CLOSED'")) {
    throw 'Long-observation deadline contract is missing.'
}

Write-Output 'PASS'
