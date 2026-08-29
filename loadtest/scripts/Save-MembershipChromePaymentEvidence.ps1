[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(0, 15)]
    [int] $UserIndex,
    [Parameter(Mandatory = $true)]
    [string] $OrderId,
    [ValidateSet('GO', 'PLUS', 'PRO', 'MAX')]
    [string] $ExpectedMembershipTier = 'GO',
    [Parameter(Mandatory = $true)]
    [string] $NetworkEvidencePath,
    [string] $MainBaseUrl = 'https://niko000o.site',
    [string] $UsersCsv = 'loadtest/local/loadtest-users.csv',
    [Parameter(Mandatory = $true)]
    [string] $OutputFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$usersPath = if ([IO.Path]::IsPathRooted($UsersCsv)) { $UsersCsv } else { Join-Path $repoRoot $UsersCsv }
$rows = @(Import-Csv -LiteralPath $usersPath)
if ($rows.Count -ne 16) { throw 'Chrome evidence requires sixteen approved token rows.' }
$token = [string]$rows[$UserIndex].accessToken
if ([string]::IsNullOrWhiteSpace($token)) { throw 'Chrome evidence token is empty.' }
if (-not (Test-Path -LiteralPath $NetworkEvidencePath -PathType Leaf)) {
    throw 'Chrome network evidence file is missing.'
}
$response = Invoke-WebRequest `
    -UseBasicParsing `
    -Uri ($MainBaseUrl.TrimEnd('/') + "/api/user/membership-orders/$OrderId") `
    -Headers @{ Authorization = "Bearer $token"; Accept = 'application/json' } `
    -TimeoutSec 30
$order = $response.Content | ConvertFrom-Json
if ([string]$order.status -ne 'PAID') { throw 'Chrome payment order is not PAID.' }
if ([string]$order.membershipTier -ne $ExpectedMembershipTier) {
    throw "Chrome payment order did not reach the planned $ExpectedMembershipTier tier."
}
[ordered]@{
    verdict = 'PASS'
    browserType = 'extension'
    userIndex = $UserIndex
    userId = [string]$rows[$UserIndex].userId
    orderId = $OrderId
    status = [string]$order.status
    membershipTier = [string]$order.membershipTier
    expectedMembershipTier = $ExpectedMembershipTier
    networkEvidencePath = (Resolve-Path $NetworkEvidencePath).Path
    verifiedAt = [datetimeoffset]::UtcNow.ToString('O')
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputFile -Encoding UTF8
$token = $null
