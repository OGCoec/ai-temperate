[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$inputPath = Join-Path $repositoryRoot 'loadtest\input\membership-entitlement-resolution-cases.csv'
$runnerPath = Join-Path $repositoryRoot 'loadtest\scripts\run-membership-entitlement-resolution-matrix.ps1'
$invokerPath = Join-Path $repositoryRoot 'loadtest\scripts\Invoke-MembershipLoadtestScenario.ps1'
$verificationPath = Join-Path $repositoryRoot 'loadtest\sql\verify-membership-entitlement-resolution.sql'

foreach ($requiredPath in @($inputPath, $runnerPath, $invokerPath, $verificationPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Missing entitlement-resolution matrix artifact: $requiredPath"
    }
}

$rows = @(Import-Csv -LiteralPath $inputPath)
if ($rows.Count -ne 40) {
    throw "Entitlement-resolution matrix must contain exactly 40 orders; received $($rows.Count)."
}
if (@($rows | Group-Object scenario | Where-Object Count -ne 1).Count -gt 0) {
    throw 'Every entitlement-resolution scenario name must be unique.'
}

$expectedGroups = [ordered]@{
    'NG-NOATTEMPT-' = 5
    'NG-ATTEMPT-' = 5
    'NG-PENDING-REJECTED-' = 5
    'NG-CLOSING-REJECTED-' = 5
    'RR-CANCEL-LATE-' = 5
    'RR-CANCEL-RACE-' = 5
    'RR-CLOSED-LATE-' = 5
    'RR-HARDCLOSE-LATE-' = 5
}
foreach ($prefix in $expectedGroups.Keys) {
    $count = @($rows | Where-Object { [string]$_.scenario -like "$prefix*" }).Count
    if ($count -ne $expectedGroups[$prefix]) {
        throw "Scenario prefix $prefix must contain exactly $($expectedGroups[$prefix]) rows; received $count."
    }
}

$notGrantedRows = @($rows | Where-Object { [string]$_.scenario -like 'NG-*' })
$refundRequiredRows = @($rows | Where-Object { [string]$_.scenario -like 'RR-*' })
if ($notGrantedRows.Count -ne 20 -or $refundRequiredRows.Count -ne 20) {
    throw 'The matrix must contain exactly 20 NOT_GRANTED and 20 REFUND_REQUIRED orders.'
}
if (@($notGrantedRows | Where-Object {
            $_.expectedStatus -ne 'CLOSED' -or
            $_.expectedResolution -notin @('NONE', 'REJECTED')
        }).Count -gt 0) {
    throw 'NOT_GRANTED cases must close without a payable callback or after a REJECTED callback.'
}
if (@($refundRequiredRows | Where-Object {
            $_.expectedResolution -ne 'REFUND_REQUIRED' -or
            $_.expectedStatus -notin @('CANCELLED', 'CLOSED')
        }).Count -gt 0) {
    throw 'REFUND_REQUIRED cases must remain CANCELLED or CLOSED after a late successful payment callback.'
}

$runner = Get-Content -Raw -LiteralPath $runnerPath
foreach ($requiredRunnerFragment in @(
        "-Scenario 'membership-entitlement-resolution-matrix'",
        '[int] $Threads = 40',
        '[int] $Port = 6655')) {
    if (-not $runner.Contains($requiredRunnerFragment)) {
        throw "Entitlement-resolution runner is missing: $requiredRunnerFragment"
    }
}

$invoker = Get-Content -Raw -LiteralPath $invokerPath
foreach ($requiredInvokerFragment in @(
        "'membership-entitlement-resolution-matrix' { return 40 }",
        "'membership-entitlement-resolution-cases.csv'",
        "'verify-membership-entitlement-resolution.sql'")) {
    if (-not $invoker.Contains($requiredInvokerFragment)) {
        throw "Scenario invoker is missing entitlement-resolution contract: $requiredInvokerFragment"
    }
}

$verification = Get-Content -Raw -LiteralPath $verificationPath
foreach ($requiredVerificationFragment in @(
        'COUNT(*) FROM membership_entitlement_resolution_scope) <> 40',
        "entitlement_resolution IS DISTINCT FROM 'NOT_GRANTED'",
        "entitlement_resolution IS DISTINCT FROM 'REFUND_REQUIRED'",
        'membership_tier <> 0',
        'HAVING COUNT(callback.id) > 1',
        "SELECT 'PASS' AS verdict")) {
    if (-not $verification.Contains($requiredVerificationFragment)) {
        throw "Entitlement-resolution verifier is missing: $requiredVerificationFragment"
    }
}

Write-Output 'PASS: entitlement-resolution matrix contract is complete.'
