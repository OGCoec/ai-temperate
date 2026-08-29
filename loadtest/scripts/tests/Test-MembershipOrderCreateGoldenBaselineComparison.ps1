[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$reporter = Join-Path $repositoryRoot `
    'loadtest\scripts\New-MembershipOrderCreateGoldenBaselineComparison.ps1'
$goldenRoot = Join-Path $repositoryRoot `
    'loadtest-output\soak\membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048\millisecond-boundary'
if (-not (Test-Path -LiteralPath $reporter -PathType Leaf)) {
    throw "Golden comparison reporter is missing: $reporter"
}
if (-not (Test-Path -LiteralPath $goldenRoot -PathType Container)) {
    throw "Golden evidence root is missing: $goldenRoot"
}

$temp = Join-Path ([IO.Path]::GetTempPath()) `
    ('membership-golden-comparison-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null

function New-OrderId([long] $Value) {
    $bytes = [byte[]]::new(16)
    [BitConverter]::GetBytes($Value).CopyTo($bytes, 8)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

try {
    $scenario = Join-Path $temp 'scenario-orders.csv'
    $http = Join-Path $temp 'http.log'
    $focused = Join-Path $temp 'focused-summary.csv'
    $scenarioWriter = [IO.StreamWriter]::new($scenario, $false, [Text.UTF8Encoding]::new($false))
    $httpWriter = [IO.StreamWriter]::new($http, $false, [Text.UTF8Encoding]::new($false))
    try {
        $scenarioWriter.WriteLine('run_id,group_code,user_id,trace_id,order_id')
        $base = 1787763000000000L
        $httpWriter.WriteLine(
            "event=membership_order_create_http_completed v=1 r=application-run sg=E-P1 tr=trace-from-prior-stage recv=$base done=$($base+100L) dur=100 status=201 committed=true")
        for ($index = 0; $index -lt 5000; $index += 1) {
            $trace = "trace-current-$($index.ToString('D5'))"
            $orderId = New-OrderId ($index + 1L)
            $userId = 70000000000000000L + $index
            $scenarioWriter.WriteLine("current-run,E-P1,$userId,$trace,$orderId")
            $received = $base + $index * 900L
            $completed = $received + 400000L
            $httpWriter.WriteLine(
                "event=membership_order_create_http_completed v=1 r=application-run sg=E-P1 tr=$trace recv=$received done=$completed dur=$($completed-$received) status=201 committed=true")
        }
    } finally {
        $scenarioWriter.Dispose()
        $httpWriter.Dispose()
    }
    @([pscustomobject][ordered]@{
        operation='ORDER_CREATE'; attemptCount=5000; uniqueOrderCount=5000;
        successCount=5000; p50Ms=160; p95Ms=300; p99Ms=390;
        redisWriteQueueWaitP50Ms=45; redisWriteQueueWaitP95Ms=150; redisWriteQueueWaitP99Ms=210;
        redisPipelineExecuteP50Ms=70; redisPipelineExecuteP95Ms=125; redisPipelineExecuteP99Ms=165;
        rabbitPublishConfirmP50Ms=30; rabbitPublishConfirmP95Ms=82; rabbitPublishConfirmP99Ms=155;
        rabbitConfirmWaitP50Ms=20; rabbitConfirmWaitP95Ms=56; rabbitConfirmWaitP99Ms=85;
        dbTransactionP50Ms=1.5; dbTransactionP95Ms=4; dbTransactionP99Ms=12
    }) | Export-Csv -LiteralPath $focused -NoTypeInformation -Encoding utf8

    & $reporter -RunId 'current-run' -GroupCode 'E-P1' `
        -ExecutionPhase 'FORMAL' -HttpLogRunId 'application-run' `
        -ScenarioOrdersCsvPath $scenario `
        -HttpEventsLogPath $http -FocusedSummaryCsvPath $focused `
        -GoldenBaselineRunId 'membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048' `
        -GoldenBaselineEvidenceRoot $goldenRoot -OutputDirectory $temp `
        -AllowEventsOutsideScenarioManifest
    $report = Get-Content -Raw -LiteralPath (
        Join-Path $temp 'golden-baseline-comparison.json') | ConvertFrom-Json
    if (-not [bool]$report.goldenEvidenceValidated -or
            [long]$report.goldenEvidence.uniqueCommittedHttp201 -ne 5000L -or
            -not [bool]$report.goldenEvidence.windowsAgreeWithRawHttpBoundaries -or
            [long]$report.golden.wallClockMicros -ne 4909846L -or
            [Math]::Abs([double]$report.golden.qps - 1018.362D) -gt 0.001D -or
            [string]$report.contractVerdict -ne 'PASS' -or
            [string]$report.goldenReproduction -ne 'REPRODUCED' -or
            [string]$report.current.httpLogRunId -ne 'application-run' -or
            [long]$report.current.outsideScenarioHttpEventCount -ne 1L -or
            $null -eq $report.deltas.orderCreateP95Ms -or
            $null -eq $report.current.frontHalfQps -or
            $null -eq $report.current.backHalfQps) {
        throw 'Golden comparison reporter did not validate or classify the current 5K sample.'
    }
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output 'PASS'
