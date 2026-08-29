$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptPath = Join-Path $PSScriptRoot '..\Start-MembershipPaymentSoakLocalPhase.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw 'Local membership soak orchestrator is missing.'
}

$source = Get-Content -Raw -LiteralPath $scriptPath
$requiredContracts = @(
    'Order-UsersByMembershipTier',
    'SELECT login_identity_id, membership_tier',
    'Sort-Object',
    'Export-Csv',
    'Order-UsersByMembershipTier $UsersCsv'
)
foreach ($contract in $requiredContracts) {
    if (-not $source.Contains($contract)) {
        throw "Local soak account ordering contract is missing: $contract"
    }
}

if ($source.Contains('UPDATE user_membership_quota')) {
    throw 'Wave account ordering must be read-only and cannot reset membership state.'
}

Write-Output 'PASS'
