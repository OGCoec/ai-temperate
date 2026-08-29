$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptPath = Join-Path $PSScriptRoot '..\jmeter\membership-marker-stage-matrix.groovy'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw 'Membership Marker matrix Groovy script is missing.'
}

$source = Get-Content -Raw -LiteralPath $scriptPath
$requiredContracts = @(
    'createOrderAfterPersistenceBarrier',
    'createResponse.status == 409',
    'Instant.now().plusSeconds(30L)',
    'waitForEntitlementVisibility',
    'Instant.now().plusSeconds(60L)',
    "vars.get('markerResolution') == 'APPLIED'",
    'waitForEntitlementVisibility(targetTier)'
)
foreach ($contract in $requiredContracts) {
    if (-not $source.Contains($contract)) {
        throw "Marker matrix account reuse contract is missing: $contract"
    }
}

if (-not $source.Contains("'POST', '/api/user/membership-orders', headers, body")) {
    throw 'Marker matrix must create orders through the public API.'
}
if ($source.Contains('UPDATE membership_order')) {
    throw 'Marker matrix must not bypass the public API with direct database updates.'
}

Write-Output 'PASS'
