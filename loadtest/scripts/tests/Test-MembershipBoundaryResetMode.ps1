[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$resetContractPath = Join-Path $repositoryRoot `
    'loadtest\scripts\MembershipBoundaryReset.ps1'
if (-not (Test-Path -LiteralPath $resetContractPath -PathType Leaf)) {
    throw "Boundary reset contract is missing: $resetContractPath"
}
. $resetContractPath

function Assert-Throws([scriptblock] $Action, [string] $ExpectedFragment) {
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notlike "*$ExpectedFragment*") {
            throw "Unexpected reset-contract error: $($_.Exception.Message)"
        }
        return
    }
    throw "Expected reset-contract failure containing: $ExpectedFragment"
}

$terminalWithoutCallbacks = Resolve-MembershipBoundaryResetMode `
    -ManifestOrderCount 5000 `
    -DatabaseFacts '5000|5000|0|5000|0|0'
if ($terminalWithoutCallbacks.mode -ne 'TERMINAL_RESOLVED' -or
        $terminalWithoutCallbacks.endpointPath -ne 'reset' -or
        $terminalWithoutCallbacks.callbackCount -ne 0) {
    throw 'Terminal orders without callbacks must use the normal exact reset.'
}

$terminalWithCallbacks = Resolve-MembershipBoundaryResetMode `
    -ManifestOrderCount 40000 `
    -DatabaseFacts '40000|40000|0|40000|40000|0'
if ($terminalWithCallbacks.mode -ne 'TERMINAL_RESOLVED' -or
        $terminalWithCallbacks.callbackCount -ne 40000) {
    throw 'A fully converged run must retain its exact callback count.'
}

$pendingFailedRun = Resolve-MembershipBoundaryResetMode `
    -ManifestOrderCount 5000 `
    -DatabaseFacts '5000|5000|5000|0|0|0'
if ($pendingFailedRun.mode -ne 'FAILED_PENDING' -or
        $pendingFailedRun.endpointPath -ne 'failed-run-reset') {
    throw 'A stopped all-PENDING run must use the dedicated failed-run reset.'
}

Assert-Throws -ExpectedFragment 'single safe reset state' -Action {
    Resolve-MembershipBoundaryResetMode `
        -ManifestOrderCount 5000 `
        -DatabaseFacts '5000|5000|2500|2500|0|0'
}
Assert-Throws -ExpectedFragment 'single safe reset state' -Action {
    Resolve-MembershipBoundaryResetMode `
        -ManifestOrderCount 5000 `
        -DatabaseFacts '5000|5000|0|0|0|0'
}
Assert-Throws -ExpectedFragment 'unresolved callbacks' -Action {
    Resolve-MembershipBoundaryResetMode `
        -ManifestOrderCount 5000 `
        -DatabaseFacts '5000|5000|0|5000|1|1'
}
Assert-Throws -ExpectedFragment 'manifest cardinality' -Action {
    Resolve-MembershipBoundaryResetMode `
        -ManifestOrderCount 5000 `
        -DatabaseFacts '4999|4999|0|4999|0|0'
}

Write-Output 'PASS: previous boundary reset modes remain exact and state-safe.'
