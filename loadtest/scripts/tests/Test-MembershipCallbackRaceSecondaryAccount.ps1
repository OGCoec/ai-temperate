$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptPath = Join-Path $PSScriptRoot '..\jmeter\membership-callback-race-idempotency.groovy'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw 'Membership callback identity Groovy script is missing.'
}

$source = Get-Content -Raw -LiteralPath $scriptPath
$requiredContracts = @(
    "props.getProperty('RACE_CASE_COUNT', '4')",
    'reservedPrimaryCount',
    'approvedRows[reservedPrimaryCount]'
)
foreach ($contract in $requiredContracts) {
    if (-not $source.Contains($contract)) {
        throw "Callback identity secondary account contract is missing: $contract"
    }
}

Write-Output 'PASS'
