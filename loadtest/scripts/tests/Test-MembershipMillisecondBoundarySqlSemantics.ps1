[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    throw 'Required command is unavailable: psql'
}
$postgresUrl = if ($env:MEMBERSHIP_PAYMENT_POSTGRES_URL) {
    $env:MEMBERSHIP_PAYMENT_POSTGRES_URL
} elseif ($env:POSTGRES_URL) {
    $env:POSTGRES_URL
} else {
    'postgresql://postgres@127.0.0.1:5431/ai_temperate'
}

$sql = @'
WITH boundary_cases(received_at, hard_close_at, expected_resolution) AS (
    VALUES
        ('2026-08-23T18:22:00.123455Z'::TIMESTAMPTZ(6),
         '2026-08-23T18:22:00.123456Z'::TIMESTAMPTZ(6), 'APPLIED'),
        ('2026-08-23T18:22:00.123456Z'::TIMESTAMPTZ(6),
         '2026-08-23T18:22:00.123456Z'::TIMESTAMPTZ(6), 'REFUND_REQUIRED'),
        ('2026-08-23T18:22:00.123457Z'::TIMESTAMPTZ(6),
         '2026-08-23T18:22:00.123456Z'::TIMESTAMPTZ(6), 'REFUND_REQUIRED')
), verdict AS (
    SELECT expected_resolution,
           CASE WHEN received_at < hard_close_at
                THEN 'APPLIED' ELSE 'REFUND_REQUIRED' END AS actual_resolution
    FROM boundary_cases
)
SELECT COUNT(*) FILTER (WHERE expected_resolution <> actual_resolution)
FROM verdict;

WITH closing_cases(closing_deadline_at, planned_hard_close_at, expected_failure) AS (
    VALUES
        (NULL::TIMESTAMPTZ(6), '2026-08-23T18:22:00.123456Z'::TIMESTAMPTZ(6), FALSE),
        ('2026-08-23T18:22:00.123456Z'::TIMESTAMPTZ(6),
         '2026-08-23T18:22:00.123456Z'::TIMESTAMPTZ(6), FALSE),
        ('2026-08-23T18:22:00.123455Z'::TIMESTAMPTZ(6),
         '2026-08-23T18:22:00.123456Z'::TIMESTAMPTZ(6), TRUE)
), verdict AS (
    SELECT expected_failure,
           closing_deadline_at IS NOT NULL
               AND closing_deadline_at IS DISTINCT FROM planned_hard_close_at AS actual_failure
    FROM closing_cases
)
SELECT COUNT(*) FILTER (WHERE expected_failure <> actual_failure)
FROM verdict;

WITH provider_cases(
        resolution,
        order_provider_trade_no,
        callback_provider_trade_no,
        dispatch_provider_trade_no,
        expected_failure) AS (
    VALUES
        ('APPLIED', 'E-P1-MMB-run-1', 'E-P1-MMB-run-1', 'E-P1-MMB-run-1', FALSE),
        ('REFUND_REQUIRED', NULL, 'H-AR-MMB-run-2', 'H-AR-MMB-run-2', FALSE),
        ('NOT_GRANTED', NULL, 'H-AR-MMB-run-3', 'H-AR-MMB-run-3', FALSE),
        ('REFUND_REQUIRED', 'H-AR-MMB-run-4', 'H-AR-MMB-run-4',
         'H-AR-MMB-run-4', TRUE),
        ('APPLIED', NULL, 'E-P1-MMB-run-5', 'E-P1-MMB-run-5', TRUE)
), verdict AS (
    SELECT expected_failure,
           callback_provider_trade_no IS NULL
               OR callback_provider_trade_no IS DISTINCT FROM dispatch_provider_trade_no
               OR (resolution = 'APPLIED' AND (
                   order_provider_trade_no IS NULL
                   OR order_provider_trade_no IS DISTINCT FROM callback_provider_trade_no))
               OR (resolution IN ('REFUND_REQUIRED', 'NOT_GRANTED')
                   AND order_provider_trade_no IS NOT NULL) AS actual_failure
    FROM provider_cases
)
SELECT COUNT(*) FILTER (WHERE expected_failure <> actual_failure)
FROM verdict;
'@
$rows = @(& psql -w $postgresUrl -v ON_ERROR_STOP=1 -A -t -c $sql)
if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 3 -or @($rows | Where-Object { $_.Trim() -ne '0' }).Count -ne 0) {
    throw 'PostgreSQL microsecond boundary semantics are inconsistent with the verifier contract.'
}

Write-Output 'PASS: PostgreSQL verifier preserves microsecond boundaries and clears non-granted order trade numbers.'
