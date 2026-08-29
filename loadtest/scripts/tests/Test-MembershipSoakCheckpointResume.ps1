$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptPath = Join-Path $PSScriptRoot '..\Start-MembershipPaymentSoakLocalPhase.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw 'Local soak orchestrator is missing.'
}

$source = Get-Content -Raw -LiteralPath $scriptPath
$requiredContracts = @(
    "[ValidateSet('W01', 'W02', 'W03', 'W04', 'W05', 'W06', 'W07', 'W08')]",
    "[string] `$StartWave = 'W01'",
    '[switch] $StartImmediately',
    '[switch] $NoWaveGaps',
    "if (`$StartWave -eq 'W01')",
    'Assert-NoExistingActiveOrders',
    'local-wave-results.json',
    'resume-source-fingerprint',
    "`$startWaveIndex -le [array]::IndexOf(`$waveOrder, 'W02')",
    "Wait-ToWaveStartBoundary `$startedAt ([timespan]::FromMinutes(62)) 'W01' 'W02'",
    'Wait-ToWaveStartBoundary',
    "if (`$NoWaveGaps -or (`$Resume -and `$StartImmediately -and `$RequestedStartWave -eq `$TargetWave))",
    "if (-not `$NoWaveGaps.IsPresent)",
    'Import-CompletedSplitWaveResults',
    "@('W03-A', 'W03-B')",
    '$Results | ConvertTo-Json -Depth 8'
)
foreach ($contract in $requiredContracts) {
    if (-not $source.Contains($contract)) {
        throw "Local soak checkpoint-resume contract is missing: $contract"
    }
}

$forbiddenContracts = @(
    "if (`$Resume -and (`$NoWaveGaps -or (`$StartImmediately -and `$RequestedStartWave -eq `$TargetWave)))",
    "if (-not (`$isResume -and `$NoWaveGaps.IsPresent))",
    'if ($LASTEXITCODE -ne 0) { throw "$Wave failed with exit code $LASTEXITCODE." }'
)
foreach ($contract in $forbiddenContracts) {
    if ($source.Contains($contract)) {
        throw "Local soak no-gap contract still depends on checkpoint resume: $contract"
    }
}

Write-Output 'PASS'
