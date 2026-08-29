[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $LocalDeploymentGate,
    [Parameter(Mandatory = $true)]
    [string] $DeploymentFingerprint,
    [string] $MainBaseUrl = 'https://niko000o.site',
    [string] $BarBaseUrl = 'https://ihaveagoddamnplan.com',
    [string] $UsersCsv = 'loadtest/local/loadtest-users.csv',
    [string] $CredentialFile = 'C:\Users\damn\AppData\Local\Temp\新建文件夹\新建 Text Document.txt',
    [string] $OperatorEvidenceDirectory = 'loadtest/local/bar-operator-evidence',
    [string] $PostgresUrl = '',
    [string] $SoakId = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$caseScript = Join-Path $PSScriptRoot 'Invoke-MembershipBarOrderCase.ps1'
$usersPath = if ([IO.Path]::IsPathRooted($UsersCsv)) { $UsersCsv } else { Join-Path $repoRoot $UsersCsv }
$operatorRoot = if ([IO.Path]::IsPathRooted($OperatorEvidenceDirectory)) {
    $OperatorEvidenceDirectory
} else {
    Join-Path $repoRoot $OperatorEvidenceDirectory
}
$gate = Get-Content -Raw -LiteralPath $LocalDeploymentGate | ConvertFrom-Json
if ($gate.verdict -ne 'PASS' -or $gate.phase -ne 'LOCAL') {
    throw 'BAR phase requires a PASS local deployment gate.'
}
if ([string]$gate.sourceFingerprint -ne $DeploymentFingerprint) {
    throw 'Deployed fingerprint does not match the completed local phase.'
}
if (-not (Test-Path -LiteralPath $CredentialFile -PathType Leaf)) {
    throw 'BAR credential file is unavailable.'
}
$tokens = @(Import-Csv -LiteralPath $usersPath)
if ($tokens.Count -ne 16 -or @($tokens | Where-Object { [string]::IsNullOrWhiteSpace($_.accessToken) }).Count -gt 0) {
    throw 'BAR phase requires sixteen non-empty approved access tokens.'
}

if ([string]::IsNullOrWhiteSpace($SoakId)) { $SoakId = [string]$gate.soakId }
if ([string]::IsNullOrWhiteSpace($SoakId)) {
    $SoakId = 'membership-payment-bar-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
}
$outputRoot = Join-Path $repoRoot "loadtest-output/soak/$SoakId/bar"
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
New-Item -ItemType Directory -Force -Path $operatorRoot | Out-Null
$manifestPath = Join-Path $outputRoot 'bar-manifest.json'
$statePath = Join-Path $outputRoot 'soak-state.json'
$resultsPath = Join-Path $outputRoot 'bar-wave-results.json'
$finalVerdictPath = Join-Path $outputRoot 'bar-verdict.json'
$infrastructureVerdictPath = Join-Path $outputRoot 'infrastructure-verdict.json'
$localFormalStart = [datetimeoffset][string]$gate.formalStartedAt
$barNotBefore = $localFormalStart.AddHours(11.5)
while ([datetimeoffset]::UtcNow -lt $barNotBefore) {
    $remaining = $barNotBefore - [datetimeoffset]::UtcNow
    Start-Sleep -Seconds ([Math]::Max(1, [Math]::Min(60, [int][Math]::Ceiling($remaining.TotalSeconds))))
}
$startedAt = [datetimeoffset]::UtcNow
$results = [System.Collections.Generic.List[object]]::new()
$script:autoSuccessCursor = 0
$script:autoSuccessUsers = @(
    2, 3,
    0, 1, 2, 3, 8, 9, 10,
    11, 0, 1, 2,
    3, 8, 9, 10, 11, 0,
    1, 2, 3, 10
)
$script:otherCursor = 0
$script:otherUserRing = @(0, 1, 2, 3, 10, 11)
$script:caseSequence = 0

function Save-State([string] $State, [string] $Wave, [datetimeoffset] $Boundary) {
    [ordered]@{
        phase = 'BAR'
        state = $State
        wave = $Wave
        startedAt = $startedAt.ToString('O')
        nextBoundaryAt = $Boundary.ToUniversalTime().ToString('O')
        updatedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8
}

function Wait-ToOffset([timespan] $Offset, [string] $Wave) {
    $boundary = $startedAt.Add($Offset)
    while ([datetimeoffset]::UtcNow -lt $boundary) {
        Save-State 'OBSERVING' $Wave $boundary
        $remaining = $boundary - [datetimeoffset]::UtcNow
        Start-Sleep -Seconds ([Math]::Max(1, [Math]::Min(60, [int][Math]::Ceiling($remaining.TotalSeconds))))
    }
}

function Wait-ForEvidence([string] $Path, [datetimeoffset] $Deadline, [string] $Description) {
    while (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        if ([datetimeoffset]::UtcNow -ge $Deadline) {
            throw "$Description evidence did not arrive before its gate deadline."
        }
        Save-State 'WAITING_FOR_OPERATOR_EVIDENCE' $Description $Deadline
        Start-Sleep -Seconds 15
    }
    $evidence = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    if ($evidence.verdict -ne 'PASS') { throw "$Description evidence verdict is not PASS." }
    return $evidence
}

function New-Case(
    [string] $Wave,
    [string] $Action,
    [string] $ExpectedStatus,
    [string] $Anchor = 'CREATED',
    [int] $OffsetSeconds = 5,
    [int] $ReplayCount = 0,
    [int] $PreferredUserIndex = -1,
    [string] $PreferredTargetTier = 'NEXT') {
    $script:caseSequence++
    $userIndex = if ($PreferredUserIndex -ge 0) {
        $PreferredUserIndex
    } elseif ($Action -in @('PAY', 'PAY_REPLAY_NOTIFY')) {
        if ($script:autoSuccessCursor -ge $script:autoSuccessUsers.Count) {
            throw 'BAR success account capacity plan has been exhausted.'
        }
        $selected = $script:autoSuccessUsers[$script:autoSuccessCursor]
        $script:autoSuccessCursor++
        $selected
    } else {
        $selected = $script:otherUserRing[
            $script:otherCursor % $script:otherUserRing.Count]
        $script:otherCursor++
        $selected
    }
    return [pscustomobject]@{
        wave = $Wave
        caseName = ('{0}-{1:D3}' -f $Wave, $script:caseSequence)
        userIndex = $userIndex
        action = $Action
        expectedStatus = $ExpectedStatus
        targetTier = $PreferredTargetTier
        payType = if ($script:caseSequence % 2 -eq 0) { 'wxpay' } else { 'alipay' }
        actionAnchor = $Anchor
        actionOffsetSeconds = $OffsetSeconds
        payAfterCancelSeconds = 2
        notifyReplayCount = $ReplayCount
    }
}

function Add-RegularCases(
    [System.Collections.Generic.List[object]] $Cases,
    [string] $Wave,
    [int] $Paid,
    [int] $Unpaid,
    [int] $Cancelled) {
    for ($index = 0; $index -lt $Paid; $index++) {
        $Cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' (10 + $index)))
    }
    for ($index = 0; $index -lt $Unpaid; $index++) {
        $Cases.Add((New-Case $Wave 'UNPAID' 'CLOSED' 'CREATED' 5))
    }
    for ($index = 0; $index -lt $Cancelled; $index++) {
        $Cases.Add((New-Case $Wave 'CANCEL' 'CANCELLED' 'CREATED' (5 + $index)))
    }
}

function New-WaveCases([string] $Wave) {
    $cases = [System.Collections.Generic.List[object]]::new()
    $script:otherCursor = 0
    $script:otherUserRing = switch ($Wave) {
        'W15' { @(1, 2, 3, 10, 11) }
        'W16' { @(11) }
        default { @(0, 1, 2, 3, 10, 11) }
    }
    switch ($Wave) {
        'W09' { Add-RegularCases $cases $Wave 2 2 2 }
        'W11' {
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 10 0 4 'GO'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 11 0 5 'PLUS'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 12 0 6 'PRO'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 13 0 7 'MAX'))
            foreach ($index in 8..11) {
                $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' (10 + $index) 0 $index 'NEXT'))
            }
            Add-RegularCases $cases $Wave 0 4 4
        }
        'W12' {
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 10 0 4 'PLUS'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 11 0 5 'PRO'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 12 0 6 'MAX'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 13 0 4 'PRO'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 14 0 5 'MAX'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 15 0 4 'MAX'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 16 0 8 'NEXT'))
            $cases.Add((New-Case $Wave 'PAY' 'PAID' 'CREATED' 17 0 9 'NEXT'))
            Add-RegularCases $cases $Wave 0 4 4
        }
        'W13' {
            $timings = @(
                @('CREATED', 120, 'PAY', 'PAID'),
                @('EXPIRES', -10, 'PAY', 'PAID'),
                @('EXPIRES', -1, 'PAY', 'PAID'),
                @('EXPIRES', 1, 'PAY', 'PAID'),
                @('EXPIRES', 150, 'PAY', 'PAID'),
                @('HARD_CLOSE', -10, 'PAY', 'PAID'),
                @('HARD_CLOSE', -1, 'PAY', 'PAID'),
                @('HARD_CLOSE', 1, 'PAY_EXPECT_REJECTED', 'CLOSED')
            )
            foreach ($timing in $timings) {
                $cases.Add((New-Case `
                    $Wave `
                    ([string]$timing[2]) `
                    ([string]$timing[3]) `
                    ([string]$timing[0]) `
                    ([int]$timing[1])))
            }
            Add-RegularCases $cases $Wave 0 4 4
        }
        'W14' {
            for ($index = 0; $index -lt 4; $index++) {
                $cases.Add((New-Case $Wave 'PAY_REPLAY_NOTIFY' 'PAID' 'CREATED' (10 + $index) 5))
            }
            for ($index = 0; $index -lt 2; $index++) {
                $cases.Add((New-Case $Wave 'CANCEL_THEN_PAY' 'CANCELLED' 'CREATED' (10 + $index)))
            }
            Add-RegularCases $cases $Wave 0 5 5
        }
        'W15' { Add-RegularCases $cases $Wave 6 9 9 }
        'W16' { Add-RegularCases $cases $Wave 4 4 4 }
        default { throw "Unsupported BAR wave: $Wave" }
    }
    return $cases
}

function Invoke-CaseWave([string] $Wave, [System.Collections.Generic.List[object]] $Cases) {
    $waveRoot = Join-Path $outputRoot $Wave
    New-Item -ItemType Directory -Force -Path $waveRoot | Out-Null
    $Cases | Export-Csv -LiteralPath (Join-Path $waveRoot 'scenario-input.csv') -NoTypeInformation -Encoding UTF8
    $jobs = [System.Collections.Generic.List[object]]::new()
    foreach ($group in ($Cases | Group-Object userIndex)) {
        $groupCases = @($group.Group)
        $job = Start-Job -ScriptBlock {
            param($ScriptPath, $SerializedCases, $Shared)
            $ErrorActionPreference = 'Stop'
            foreach ($case in @($SerializedCases)) {
                $outputFile = Join-Path $Shared.outputRoot ($case.caseName + '.json')
                & $ScriptPath `
                    -CaseName ([string]$case.caseName) `
                    -UserIndex ([int]$case.userIndex) `
                    -Action ([string]$case.action) `
                    -TargetTier ([string]$case.targetTier) `
                    -PayType ([string]$case.payType) `
                    -ActionAnchor ([string]$case.actionAnchor) `
                    -ActionOffsetSeconds ([int]$case.actionOffsetSeconds) `
                    -PayAfterCancelSeconds ([int]$case.payAfterCancelSeconds) `
                    -NotifyReplayCount ([int]$case.notifyReplayCount) `
                    -ExpectedStatus ([string]$case.expectedStatus) `
                    -MainBaseUrl $Shared.mainBaseUrl `
                    -BarBaseUrl $Shared.barBaseUrl `
                    -UsersCsv $Shared.usersCsv `
                    -CredentialFile $Shared.credentialFile `
                    -OutputFile $outputFile | Out-Null
            }
        } -ArgumentList $caseScript, $groupCases, @{
            outputRoot = $waveRoot
            mainBaseUrl = $MainBaseUrl
            barBaseUrl = $BarBaseUrl
            usersCsv = $usersPath
            credentialFile = $CredentialFile
        }
        $jobs.Add($job)
    }
    $jobs | Wait-Job | Out-Null
    $failed = @($jobs | Where-Object { $_.State -ne 'Completed' })
    foreach ($job in $jobs) { Receive-Job -Job $job -ErrorAction Continue | Out-Null }
    foreach ($job in $jobs) { Remove-Job -Job $job -Force }
    if ($failed.Count -gt 0) { throw "$Wave has $($failed.Count) failed user job(s)." }
    $evidenceFiles = @(Get-ChildItem -LiteralPath $waveRoot -Filter '*.json' -File)
    if ($evidenceFiles.Count -ne $Cases.Count) {
        throw "$Wave expected $($Cases.Count) case evidence files but found $($evidenceFiles.Count)."
    }
    foreach ($file in $evidenceFiles) {
        $evidence = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
        if ($evidence.verdict -ne 'PASS') { throw "$Wave contains a non-PASS BAR case." }
    }
    $results.Add([ordered]@{
        wave = $Wave
        verdict = 'PASS'
        actualOrders = $Cases.Count
        outputDirectory = $waveRoot
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    })
}

function Assert-RestrictedPurchaseRules {
    $probeRoot = Join-Path $outputRoot 'W16/restricted-probes'
    New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null
    $probes = [System.Collections.Generic.List[object]]::new()
    foreach ($index in 12..15) {
        $headers = @{ Authorization = "Bearer $($tokens[$index].accessToken)"; Accept = 'application/json' }
        $offers = Invoke-WebRequest -UseBasicParsing -Uri ($MainBaseUrl.TrimEnd('/') + '/api/user/membership-plan-offers') -Headers $headers -TimeoutSec 30
        if (@(($offers.Content | ConvertFrom-Json).offers).Count -ne 0) {
            throw 'EDU/TEAM account unexpectedly received a personal upgrade offer.'
        }
        $create = Invoke-WebRequest `
            -UseBasicParsing `
            -Method POST `
            -Uri ($MainBaseUrl.TrimEnd('/') + '/api/user/membership-orders') `
            -Headers $headers `
            -ContentType 'application/json' `
            -Body (@{ targetTier = 'GO'; payType = 'alipay'; idempotencyKey = [guid]::NewGuid().ToString() } | ConvertTo-Json -Compress) `
            -SkipHttpErrorCheck `
            -TimeoutSec 30
        if ([int]$create.StatusCode -notin @(400, 409)) {
            throw 'EDU/TEAM personal order creation was not rejected.'
        }
        $probes.Add([ordered]@{ userIndex = $index; offers = 0; createStatus = [int]$create.StatusCode })
    }
    foreach ($target in @('EDU', 'TEAM')) {
        $headers = @{ Authorization = "Bearer $($tokens[4].accessToken)"; Accept = 'application/json' }
        $create = Invoke-WebRequest `
            -UseBasicParsing `
            -Method POST `
            -Uri ($MainBaseUrl.TrimEnd('/') + '/api/user/membership-orders') `
            -Headers $headers `
            -ContentType 'application/json' `
            -Body (@{ targetTier = $target; payType = 'alipay'; idempotencyKey = [guid]::NewGuid().ToString() } | ConvertTo-Json -Compress) `
            -SkipHttpErrorCheck `
            -TimeoutSec 30
        if ([int]$create.StatusCode -notin @(400, 409)) {
            throw "Personal account target $target was not rejected."
        }
        $probes.Add([ordered]@{ userIndex = 4; target = $target; createStatus = [int]$create.StatusCode })
    }
    [ordered]@{ verdict = 'PASS'; probes = $probes } |
        ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $probeRoot 'verdict.json') -Encoding UTF8
}

function Assert-PersonalPurchaseRules {
    $probeRoot = Join-Path $outputRoot 'W12/personal-purchase-probes'
    New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null
    $headers = @{
        Authorization = "Bearer $($tokens[4].accessToken)"
        Accept = 'application/json'
    }
    $offers = Invoke-WebRequest `
        -UseBasicParsing `
        -Uri ($MainBaseUrl.TrimEnd('/') + '/api/user/membership-plan-offers') `
        -Headers $headers `
        -TimeoutSec 30
    if (@(($offers.Content | ConvertFrom-Json).offers).Count -ne 0) {
        throw 'U01 must have no offer after the fixed FREE-to-MAX chain.'
    }
    $probes = [System.Collections.Generic.List[object]]::new()
    foreach ($target in @('FREE', 'GO', 'PLUS', 'PRO', 'MAX', 'EDU', 'TEAM')) {
        $create = Invoke-WebRequest `
            -UseBasicParsing `
            -Method POST `
            -Uri ($MainBaseUrl.TrimEnd('/') + '/api/user/membership-orders') `
            -Headers $headers `
            -ContentType 'application/json' `
            -Body (@{
                targetTier = $target
                payType = 'alipay'
                idempotencyKey = [guid]::NewGuid().ToString()
            } | ConvertTo-Json -Compress) `
            -SkipHttpErrorCheck `
            -TimeoutSec 30
        if ([int]$create.StatusCode -notin @(400, 409)) {
            throw "MAX account target $target was not rejected."
        }
        $probes.Add([ordered]@{
            target = $target
            createStatus = [int]$create.StatusCode
        })
    }
    [ordered]@{
        verdict = 'PASS'
        currentTier = 'MAX'
        rejectedTargets = @($probes)
    } | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $probeRoot 'verdict.json') -Encoding UTF8
}

[ordered]@{
    soakId = $SoakId
    phase = 'BAR'
    formalStartedAt = $startedAt.ToString('O')
    localFormalStartedAt = [string]$gate.formalStartedAt
    deploymentFingerprint = $DeploymentFingerprint
    expectedActualOrders = 120
    browserOrders = 2
    cliOrders = 106
    concurrencyOrders = 12
    sharedConcurrencyMaximum = 50
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

try {
    $infrastructureBefore = Wait-ForEvidence `
        (Join-Path $operatorRoot 'infrastructure-before.json') `
        $startedAt.AddMinutes(30) `
        'shared BAR infrastructure baseline'
    if ([string]$infrastructureBefore.capture -ne 'BEFORE') {
        throw 'Shared BAR infrastructure baseline has an invalid capture type.'
    }

    $w09Cases = New-WaveCases 'W09'
    Invoke-CaseWave 'W09-CLI' $w09Cases
    $browserGate = Join-Path $outputRoot 'W09-browser-gate.json'
    [ordered]@{
        state = 'WAITING_FOR_EXTERNAL_CHROME_EXTENSION'
        requiredFiles = @('W09-browser-01.json', 'W09-browser-02.json')
        requiredUserIndices = @(0, 1)
        requiredTargetTier = 'GO'
        forbidden = @('Codex In-app Browser', 'Computer Use', 'hidden WebView')
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $browserGate -Encoding UTF8
    $browserOne = Wait-ForEvidence (Join-Path $operatorRoot 'W09-browser-01.json') $startedAt.AddHours(1) 'W09 Chrome 01'
    $browserTwo = Wait-ForEvidence (Join-Path $operatorRoot 'W09-browser-02.json') $startedAt.AddHours(1) 'W09 Chrome 02'
    if ($browserOne.browserType -ne 'extension' -or $browserTwo.browserType -ne 'extension') {
        throw 'W09 browser evidence was not produced by the external Chrome extension.'
    }
    if ([int]$browserOne.userIndex -ne 0 `
            -or [int]$browserTwo.userIndex -ne 1 `
            -or [string]$browserOne.membershipTier -ne 'GO' `
            -or [string]$browserTwo.membershipTier -ne 'GO' `
            -or [string]$browserOne.expectedMembershipTier -ne 'GO' `
            -or [string]$browserTwo.expectedMembershipTier -ne 'GO') {
        throw 'W09 Chrome evidence does not match the fixed FREE-to-GO account capacity plan.'
    }
    $results.Add([ordered]@{ wave = 'W09-CHROME'; verdict = 'PASS'; actualOrders = 2 })
    Wait-ToOffset ([timespan]::FromHours(1)) 'W09'

    $w10Root = Join-Path $outputRoot 'W10'
    & (Join-Path $PSScriptRoot 'Invoke-MembershipBarConcurrencyWave.ps1') `
        -MainBaseUrl $MainBaseUrl `
        -UsersCsv $usersPath `
        -OutputDirectory $w10Root
    if ($LASTEXITCODE -ne 0) { throw 'W10 BAR concurrency wave failed.' }
    $results.Add([ordered]@{ wave = 'W10'; verdict = 'PASS'; actualOrders = 12 })
    Wait-ToOffset ([timespan]::FromHours(2.5)) 'W10'

    Invoke-CaseWave 'W11' (New-WaveCases 'W11')
    Wait-ToOffset ([timespan]::FromHours(3.75)) 'W11'
    Invoke-CaseWave 'W12' (New-WaveCases 'W12')
    Assert-PersonalPurchaseRules
    Wait-ToOffset ([timespan]::FromHours(5)) 'W12'
    Invoke-CaseWave 'W13' (New-WaveCases 'W13')
    Wait-ToOffset ([timespan]::FromHours(7)) 'W13'
    Invoke-CaseWave 'W14' (New-WaveCases 'W14')
    Wait-ToOffset ([timespan]::FromHours(8.5)) 'W14'
    Invoke-CaseWave 'W15' (New-WaveCases 'W15')
    Wait-ToOffset ([timespan]::FromHours(11)) 'W15'

    $prepared = Wait-ForEvidence (Join-Path $operatorRoot 'restricted-prepared.json') $startedAt.AddHours(11.25) 'restricted fixture prepare'
    if (-not [bool]$prepared.prepared) { throw 'Restricted fixture prepare evidence is not active.' }
    Assert-RestrictedPurchaseRules
    Invoke-CaseWave 'W16' (New-WaveCases 'W16')
    $quotaEvidence = Wait-ForEvidence (Join-Path $operatorRoot 'quota-first-use.json') $startedAt.AddHours(12) 'quota first-use'
    if ([string]$quotaEvidence.source -ne 'W16_CONTROLLER_PROBE' `
            -or [string]::IsNullOrWhiteSpace([string]$quotaEvidence.jarSha256) `
            -or -not [bool]$quotaEvidence.temporaryApiKeyDeleted) {
        throw 'Quota first-use evidence was not generated by the controlled Controller probe or left its temporary API Key active.'
    }
    $requiredQuotaCases = @(
        'API_KEY_TEXT',
        'H5_TEXT',
        'IMAGE',
        'VIDEO',
        'FAILED',
        'MODEL_NOT_ALLOWED',
        'IDEMPOTENT_REPLAY',
        'TRANSACTION_ROLLBACK'
    )
    $actualQuotaCases = @($quotaEvidence.cases | ForEach-Object { [string]$_ })
    if ($actualQuotaCases.Count -ne $requiredQuotaCases.Count `
            -or @($actualQuotaCases | Sort-Object -Unique).Count `
                    -ne $requiredQuotaCases.Count `
            -or @($requiredQuotaCases | Where-Object {
                    $actualQuotaCases -notcontains $_
                }).Count -gt 0) {
        throw 'Quota first-use evidence does not cover every required entry and failure mode.'
    }
    $restored = Wait-ForEvidence (Join-Path $operatorRoot 'restricted-restored.json') $startedAt.AddHours(12) 'restricted fixture restore'
    if ([bool]$restored.prepared) { throw 'Restricted fixture restore evidence still reports prepared=true.' }
    Wait-ToOffset ([timespan]::FromHours(12)) 'W16'

    $barOrderIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($csv in @(Get-ChildItem -LiteralPath $outputRoot `
            -Filter 'scenario-orders.csv' -File -Recurse)) {
        foreach ($row in @(Import-Csv -LiteralPath $csv.FullName)) {
            if ($row.PSObject.Properties.Name -contains 'order_id' `
                -and [string]$row.order_id -match '^[A-Za-z0-9_-]{22}$') {
                [void]$barOrderIds.Add([string]$row.order_id)
            }
        }
    }
    foreach ($json in @(Get-ChildItem -LiteralPath $outputRoot `
            -Filter '*.json' -File -Recurse)) {
        $document = Get-Content -Raw -LiteralPath $json.FullName | ConvertFrom-Json
        foreach ($row in @($document)) {
            if ($null -ne $row `
                -and $row.PSObject.Properties.Name -contains 'orderId' `
                -and [string]$row.orderId -match '^[A-Za-z0-9_-]{22}$') {
                [void]$barOrderIds.Add([string]$row.orderId)
            }
        }
    }
    foreach ($browserEvidence in @($browserOne, $browserTwo)) {
        if ([string]$browserEvidence.orderId -match '^[A-Za-z0-9_-]{22}$') {
            [void]$barOrderIds.Add([string]$browserEvidence.orderId)
        }
    }
    if ($barOrderIds.Count -ne 120) {
        throw "BAR final infrastructure scan expected 120 order IDs but collected $($barOrderIds.Count)."
    }
    $barOrderIdsPath = Join-Path $operatorRoot 'bar-order-ids.json'
    [ordered]@{ orderIds = @($barOrderIds | Sort-Object) } `
        | ConvertTo-Json -Depth 3 `
        | Set-Content -LiteralPath $barOrderIdsPath -Encoding UTF8
    $infrastructureAfter = Wait-ForEvidence `
        (Join-Path $operatorRoot 'infrastructure-after.json') `
        $startedAt.AddHours(12.5) `
        'shared BAR final infrastructure'
    if ([string]$infrastructureAfter.capture -ne 'AFTER') {
        throw 'Shared BAR final infrastructure evidence has an invalid capture type.'
    }
    Wait-ToOffset ([timespan]::FromHours(12.5)) 'FINAL_SCAN'

    $finalDatabaseScanPath = Join-Path $outputRoot 'final-database-scan.txt'
    & (Join-Path $PSScriptRoot 'Invoke-MembershipPaymentFinalDatabaseScan.ps1') `
        -SoakStartedAt $startedAt `
        -ExpectedOrderCount 120 `
        -OutputFile $finalDatabaseScanPath `
        -PostgresUrl $PostgresUrl | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'BAR final PostgreSQL scan failed.' }

    & (Join-Path $PSScriptRoot 'Test-MembershipPaymentFinalInfrastructure.ps1') `
        -BeforeEvidence (Join-Path $operatorRoot 'infrastructure-before.json') `
        -AfterEvidence (Join-Path $operatorRoot 'infrastructure-after.json') `
        -ExpectedOrderCount 120 `
        -ExpectedPaymentDlqDelta 0 `
        -OutputFile $infrastructureVerdictPath
    if ($LASTEXITCODE -ne 0) { throw 'BAR final infrastructure verification failed.' }

    $actualOrders = [int](($results | Measure-Object -Property actualOrders -Sum).Sum)
    if ($actualOrders -ne 120) { throw "BAR phase expected 120 actual orders but counted $actualOrders." }
    if (([datetimeoffset]::UtcNow - $localFormalStart).TotalHours -lt 24) {
        throw 'Combined local and BAR observation has not reached twenty-four hours.'
    }
    $results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultsPath -Encoding UTF8
    [ordered]@{
        verdict = 'PASS'
        phase = 'BAR'
        soakId = $SoakId
        actualOrders = $actualOrders
        combinedObservationHours = (([datetimeoffset]::UtcNow - $localFormalStart).TotalHours)
        finalDatabaseScan = $finalDatabaseScanPath
        finalInfrastructureScan = $infrastructureVerdictPath
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $finalVerdictPath -Encoding UTF8
    & (Join-Path $PSScriptRoot 'New-MembershipPaymentSoakReport.ps1') `
        -SoakRoot (Split-Path -Parent $outputRoot) | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The twenty-four-hour aggregate report failed.' }
    Save-State 'PASS' 'COMPLETE' ([datetimeoffset]::UtcNow)
} catch {
    $results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resultsPath -Encoding UTF8
    [ordered]@{
        verdict = 'FAIL'
        phase = 'BAR'
        errorType = $_.Exception.GetType().FullName
        error = $_.Exception.Message
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $finalVerdictPath -Encoding UTF8
    Save-State 'FAIL' 'STOPPED' ([datetimeoffset]::UtcNow)
    throw
}
