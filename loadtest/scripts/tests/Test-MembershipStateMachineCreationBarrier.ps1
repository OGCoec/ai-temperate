$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptPath = Join-Path $PSScriptRoot '..\jmeter\membership-state-machine-realtime.groovy'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw 'Membership state-machine Groovy script is missing.'
}

$source = Get-Content -Raw -LiteralPath $scriptPath
$requiredContracts = @(
    'createOrderAfterPersistenceBarrier',
    "createResponse.status == 409",
    "Thread.sleep(250L)",
    "Instant.now().isBefore(createDeadline)",
    "'create order after previous terminal persistence'",
    'waitForEntitlementVisibility',
    'Instant.now().plusSeconds(60L)',
    'offer.targetTier == paidTargetTier',
    "vars.get('expectedStatus') == 'PAID'",
    "vars.get('expectedResolution') == 'APPLIED'",
    'waitForEntitlementVisibility(targetTier)'
)
foreach ($contract in $requiredContracts) {
    if (-not $source.Contains($contract)) {
        throw "State-machine creation barrier contract is missing: $contract"
    }
}

if (-not $source.Contains("'POST', '/api/user/membership-orders', headers, body")) {
    throw 'State-machine script no longer creates orders through the public API.'
}
if ($source.Contains('UPDATE membership_order')) {
    throw 'State-machine script must not bypass the public API with direct database updates.'
}

Write-Output 'PASS'
