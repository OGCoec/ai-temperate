[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $RunRoot,
    [ValidateRange(1, 65535)]
    [int] $Port = 6655,
    [string] $PostgresUrl = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedRoot = (Resolve-Path -LiteralPath $RunRoot).Path
$baseUrl = "http://127.0.0.1:$Port"
$explainPath = Join-Path $resolvedRoot 'latest-paid-order-explain.json'
$probePath = Join-Path $resolvedRoot 'latest-paid-upgrade-probe.json'
$expectedIndexName = 'idx_membership_order_latest_paid'
$modulePath = Join-Path $PSScriptRoot 'MembershipSchedulerIndexHikariEvidence.psm1'
Import-Module $modulePath -Force

function Resolve-PostgresUrl {
    if (-not [string]::IsNullOrWhiteSpace($PostgresUrl)) { return $PostgresUrl }
    if ($env:MEMBERSHIP_PAYMENT_POSTGRES_URL) {
        return $env:MEMBERSHIP_PAYMENT_POSTGRES_URL
    }
    if ($env:POSTGRES_URL) { return $env:POSTGRES_URL }
    return 'postgresql://postgres@127.0.0.1:5431/ai_temperate'
}

function Invoke-PsqlLines([string] $Sql) {
    $lines = @(& psql -w (Resolve-PostgresUrl) -X -v ON_ERROR_STOP=1 `
        -A -t -F '|' -c $Sql 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL probe command failed: $($lines -join ' ')"
    }
    return @($lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

$probeEvidence = [ordered]@{
    generatedAt = [datetimeoffset]::UtcNow.ToString('O')
    businessQueryExecuted = $false
    businessHttpStatus = $null
    selectedTier = $null
    expectedIndexName = $expectedIndexName
    tokenPage = $null
    offerCount = $null
    upgradeOfferObserved = $false
    explainVerdict = 'NOT_RUN'
    verdict = 'FAIL'
    failure = $null
}

try {
    [void](Invoke-PsqlLines 'ANALYZE membership_order;')
    $candidateSql = @'
SELECT payment_order.login_identity_id, payment_order.membership_tier
FROM membership_order AS payment_order
JOIN user_membership_quota AS quota
  ON quota.login_identity_id = payment_order.login_identity_id
WHERE payment_order.login_identity_id
      BETWEEN 70000000000000000 AND 70000000000079999
  AND payment_order.status = 2
  AND payment_order.entitlement_resolution = 'APPLIED'
  AND payment_order.paid_at IS NOT NULL
  AND payment_order.membership_tier IN (1, 4, 5)
  AND quota.membership_tier = payment_order.membership_tier
  AND quota.membership_expires_at > CURRENT_TIMESTAMP
ORDER BY payment_order.paid_at DESC, payment_order.id DESC
LIMIT 1;
'@
    $candidateLines = @(Invoke-PsqlLines $candidateSql)
    if ($candidateLines.Count -ne 1) {
        throw 'No unique APPLIED membership upgrade candidate is available.'
    }
    $candidate = @($candidateLines[0].Split('|'))
    if ($candidate.Count -ne 2) {
        throw 'The APPLIED membership upgrade candidate has an invalid shape.'
    }
    $loginIdentityId = [long]$candidate[0]
    $membershipTier = [int]$candidate[1]
    $tokenPage = [int][Math]::Floor(
        ($loginIdentityId - 70000000000000000L) / 500D)
    if ($tokenPage -lt 0 -or $tokenPage -gt 159) {
        throw 'The APPLIED candidate is outside the fixed 80K token pages.'
    }

    # Token 只停留在当前进程内存；证据仅记录页码与套餐，不写入用户 ID 或认证材料。
    $tokensResponse = Invoke-RestMethod -Method Post -TimeoutSec 60 `
        -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/tokens/$tokenPage"
    $tokens = @($tokensResponse)
    $selectedToken = @($tokens | Where-Object { [long]$_.userId -eq $loginIdentityId })
    if ($selectedToken.Count -ne 1 -or
            [string]::IsNullOrWhiteSpace([string]$selectedToken[0].accessToken)) {
        throw 'The loopback token page did not contain the selected APPLIED candidate.'
    }
    $headers = @{
        Authorization = 'Bearer ' + [string]$selectedToken[0].accessToken
        Accept = 'application/json'
    }
    $response = Invoke-WebRequest -Method Get -TimeoutSec 60 `
        -SkipHttpErrorCheck -Headers $headers `
        -Uri "$baseUrl/api/user/membership-plan-offers"
    $probeEvidence.businessHttpStatus = [int]$response.StatusCode
    if ([int]$response.StatusCode -ne 200) {
        throw "The real membership offer query returned HTTP $($response.StatusCode)."
    }
    $body = $response.Content | ConvertFrom-Json
    $offers = @($body.offers)
    $probeEvidence.businessQueryExecuted = $true
    $probeEvidence.selectedTier = $membershipTier
    $probeEvidence.tokenPage = $tokenPage
    $probeEvidence.offerCount = $offers.Count
    $probeEvidence.upgradeOfferObserved = @($offers | Where-Object {
            [string]$_.transitionType -eq 'UPGRADE'
        }).Count -gt 0
    if (-not $probeEvidence.upgradeOfferObserved) {
        throw 'The real membership offer response did not contain an upgrade offer.'
    }

    $explainSql = @"
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, login_identity_id, membership_tier, pay_amount_yuan, pay_type,
       status, idempotency_key, provider_trade_no, payment_started_at,
       expires_at, closing_deadline_at, paid_at, entitlement_resolution,
       entitlement_resolved_at, state_version, created_at, updated_at
FROM membership_order
WHERE login_identity_id = $loginIdentityId
  AND membership_tier = $membershipTier
  AND status = 2
ORDER BY paid_at DESC NULLS LAST, created_at DESC, id DESC
LIMIT 1;
"@
    $explainLines = @(Invoke-PsqlLines $explainSql)
    $explainJson = $explainLines -join "`n"
    [void]($explainJson | ConvertFrom-Json)
    $explainJson | Set-Content -LiteralPath $explainPath -Encoding UTF8
    $explainVerdict = Test-MembershipLatestPaidExplain -Path $explainPath
    $probeEvidence.explainVerdict = [string]$explainVerdict.verdict
    if ($explainVerdict.verdict -ne 'PASS' -or
            -not [bool]$explainVerdict.hasExpectedIndex) {
        throw "The latest PAID execution plan did not use $expectedIndexName."
    }
    $probeEvidence.verdict = 'PASS'
} catch {
    $probeEvidence.failure = $_.Exception.Message
    throw
} finally {
    $probeEvidence.generatedAt = [datetimeoffset]::UtcNow.ToString('O')
    $probeEvidence | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath $probePath -Encoding UTF8
}

$probeEvidence | ConvertTo-Json -Depth 8
