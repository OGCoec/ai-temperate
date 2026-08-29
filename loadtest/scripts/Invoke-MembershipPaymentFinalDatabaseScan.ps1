[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [datetimeoffset] $SoakStartedAt,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 10000)]
    [int] $ExpectedOrderCount,
    [Parameter(Mandatory = $true)]
    [string] $OutputFile,
    [string] $PostgresUrl = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sqlPath = Join-Path $repoRoot 'loadtest/sql/verify-membership-soak-final.sql'
if ([string]::IsNullOrWhiteSpace($PostgresUrl)) {
    $PostgresUrl = if (-not [string]::IsNullOrWhiteSpace(
            $env:MEMBERSHIP_PAYMENT_POSTGRES_URL)) {
        $env:MEMBERSHIP_PAYMENT_POSTGRES_URL
    } elseif (-not [string]::IsNullOrWhiteSpace($env:POSTGRES_URL)) {
        $env:POSTGRES_URL
    } else {
        'postgresql://postgres@127.0.0.1:5431/ai_temperate'
    }
}

$outputParent = Split-Path -Parent $OutputFile
if (-not [string]::IsNullOrWhiteSpace($outputParent)) {
    New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
}
$rawOutput = & psql `
    -w $PostgresUrl `
    -v ON_ERROR_STOP=1 `
    -v "soak_started_at=$($SoakStartedAt.ToUniversalTime().ToString('O'))" `
    -v "expected_order_count=$ExpectedOrderCount" `
    -f $sqlPath 2>&1
$exitCode = $LASTEXITCODE
$text = $rawOutput | Out-String
$text | Set-Content -LiteralPath $OutputFile -Encoding UTF8
if ($exitCode -ne 0 -or $text -notmatch '(?im)^\s*PASS\s*$') {
    throw 'Membership soak final PostgreSQL scan did not return PASS.'
}

[pscustomobject]@{
    verdict = 'PASS'
    expectedOrderCount = $ExpectedOrderCount
    soakStartedAt = $SoakStartedAt.ToUniversalTime().ToString('O')
    completedAt = [datetimeoffset]::UtcNow.ToString('O')
    evidenceFile = $OutputFile
}
