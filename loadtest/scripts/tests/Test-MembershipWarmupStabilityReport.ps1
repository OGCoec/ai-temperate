[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$reporter = Join-Path $repositoryRoot `
    'loadtest\scripts\New-MembershipWarmupStabilityReport.ps1'
$temp = Join-Path ([IO.Path]::GetTempPath()) `
    ('membership-warmup-stability-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null

function New-OrderId([long] $Value) {
    $bytes = [byte[]]::new(16)
    [BitConverter]::GetBytes($Value).CopyTo($bytes, 8)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Convert-EpochMicrosToText([long] $EpochMicros) {
    $millis = [long][Math]::Floor($EpochMicros / 1000D)
    return [datetimeoffset]::FromUnixTimeMilliseconds($millis).UtcDateTime.AddTicks(
        ($EpochMicros % 1000L) * 10L).ToString(
            "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'",
            [Globalization.CultureInfo]::InvariantCulture)
}

function Write-WarmupFixture(
        [string] $Root,
        [int] $Count,
        [bool] $SlowLastWindow,
        [bool] $SlowFullSegment = $false,
        [string] $HttpLogRunId = 'warmup-run',
        [bool] $IncludeOutsideTrace = $false) {
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    $scenario = Join-Path $Root 'scenario-orders.csv'
    $http = Join-Path $Root 'http.log'
    $focused = Join-Path $Root 'focused.csv'
    $requests = Join-Path $Root 'request-results.csv'
    $queues = Join-Path $Root 'queues.csv'
    $scenarioWriter = [IO.StreamWriter]::new($scenario, $false, [Text.UTF8Encoding]::new($false))
    $httpWriter = [IO.StreamWriter]::new($http, $false, [Text.UTF8Encoding]::new($false))
    $focusedWriter = [IO.StreamWriter]::new($focused, $false, [Text.UTF8Encoding]::new($false))
    $requestWriter = [IO.StreamWriter]::new($requests, $false, [Text.UTF8Encoding]::new($false))
    try {
        $scenarioWriter.WriteLine('run_id,group_code,user_id,trace_id,order_id')
        $focusedWriter.WriteLine('runId,groupCode,operation,traceId,orderIdB64,outcome,totalMs,redisWriteQueueWaitMs,redisPipelineExecuteMs,rabbitPublishConfirmMs,rabbitConfirmWaitMs,dbTransactionMs')
        $requestWriter.WriteLine('run_id,group_code,operation,http_status,success,started_at,completed_at')
        $base = 1787660000000000L
        if ($IncludeOutsideTrace) {
            $httpWriter.WriteLine(
                "event=membership_order_create_http_completed v=1 r=$HttpLogRunId sg=E-P1 tr=trace-from-prior-stage recv=$base done=$($base+100L) dur=100 status=201 committed=true")
        }
        for ($index = 0; $index -lt $Count; $index += 1) {
            $window = [int][Math]::Floor($index / 500D)
            $inside = $index % 500
            $trace = "trace-E-P1-$($index.ToString('D5'))"
            $orderId = New-OrderId ($index + 1L)
            $userId = 70000000000000000L + $index
            $scenarioWriter.WriteLine("warmup-run,E-P1,$userId,$trace,$orderId")
            # 每个 500 条窗口以 0.5 秒推进，完整 5K/10K 可分别落在合同墙钟以内。
            $windowBase = $base + $window * 500000L
            $received = $windowBase + $inside
            $completed = $received + 100L
            if ($inside -eq 499) {
                $completed = $windowBase + 500000L
                if ($SlowLastWindow -and $window -eq ($Count / 500 - 1)) {
                    $completed = $windowBase + 625000L
                }
                if ($SlowFullSegment -and $window -eq ($Count / 500 - 1)) {
                    $completed = $windowBase + $(if ($Count -eq 5000) { 1100000L } else { 1650000L })
                }
            }
            $httpWriter.WriteLine(
                "event=membership_order_create_http_completed v=1 r=$HttpLogRunId sg=E-P1 tr=$trace recv=$received done=$completed dur=$($completed-$received) status=201 committed=true")
            $focusedWriter.WriteLine(
                "warmup-run,E-P1,ORDER_CREATE,$trace,$orderId,SUCCESS,150.000,40.000,64.000,28.000,19.000,1.500")
            $paymentStart = Convert-EpochMicrosToText ($received + 10L)
            $paymentEnd = Convert-EpochMicrosToText ($received + 20L)
            $requestWriter.WriteLine(
                "warmup-run,E-P1,START_PAYMENT,201,true,$paymentStart,$paymentEnd")
        }
    } finally {
        $scenarioWriter.Dispose()
        $httpWriter.Dispose()
        $focusedWriter.Dispose()
        $requestWriter.Dispose()
    }
    @(
        [pscustomobject]@{sampledAt='2026-08-25T23:00:00.000Z';segment='WARMUP';callbackReadySize=0;callbackProcessingSize=0;dirtySize=0;dirtyProcessingSize=0;rabbitPaymentReady=0;rabbitPaymentUnacked=0;rabbitClosingReady=0;rabbitClosingUnacked=0},
        [pscustomobject]@{sampledAt='2026-08-25T23:00:00.500Z';segment='WARMUP';callbackReadySize=0;callbackProcessingSize=0;dirtySize=0;dirtyProcessingSize=0;rabbitPaymentReady=0;rabbitPaymentUnacked=0;rabbitClosingReady=0;rabbitClosingUnacked=0},
        [pscustomobject]@{sampledAt='2026-08-25T23:00:01.000Z';segment='WARMUP';callbackReadySize=0;callbackProcessingSize=0;dirtySize=0;dirtyProcessingSize=0;rabbitPaymentReady=0;rabbitPaymentUnacked=0;rabbitClosingReady=0;rabbitClosingUnacked=0}
    ) | Export-Csv -LiteralPath $queues -NoTypeInformation -Encoding utf8
    return [pscustomobject]@{
        scenario=$scenario;http=$http;focused=$focused;requests=$requests;queues=$queues
    }
}

try {
    foreach ($count in @(5000, 10000)) {
        $fixture = Write-WarmupFixture (Join-Path $temp "pass-$count") $count $false
        $output = Join-Path $temp "output-$count"
        & $reporter -RunId 'warmup-run' -GroupCode 'E-P1' `
            -ScenarioOrdersCsvPath $fixture.scenario -HttpEventsLogPath $fixture.http `
            -FocusedEventsCsvPath $fixture.focused -RequestResultsCsvPath $fixture.requests `
            -QueueSamplesCsvPath $fixture.queues -OutputDirectory $output
        $verdict = Get-Content -Raw -LiteralPath (
            Join-Path $output 'verdict.json') | ConvertFrom-Json
        $windows = @(Import-Csv -LiteralPath (
            Join-Path $output 'stability-windows.csv'))
        if ($verdict.verdict -ne 'PASS' -or $verdict.expectedCount -ne $count -or
                $windows.Count -ne ($count / 500) -or
                $verdict.lastTwoWindows.Count -ne 2 -or
                -not [bool]$verdict.lastTwoWindowsDiagnosticOnly -or
                [double]$verdict.fullHttpQps -lt 900D -or
                [string]$verdict.performanceClassification -eq 'NOT_QUALIFIED' -or
                @($windows | Where-Object { [int]$_.count -ne 500 }).Count -ne 0) {
            throw "Stable $count-order warmup was not accepted."
        }
    }

    $fixture = Write-WarmupFixture (Join-Path $temp 'slow') 5000 $true
    $output = Join-Path $temp 'slow-output'
    & $reporter -RunId 'warmup-run' -GroupCode 'E-P1' `
        -ScenarioOrdersCsvPath $fixture.scenario -HttpEventsLogPath $fixture.http `
        -FocusedEventsCsvPath $fixture.focused -RequestResultsCsvPath $fixture.requests `
        -QueueSamplesCsvPath $fixture.queues -OutputDirectory $output
    $verdict = Get-Content -Raw -LiteralPath (
        Join-Path $output 'verdict.json') | ConvertFrom-Json
    if ($verdict.verdict -ne 'PASS' -or [double]$verdict.lastTwoWindows[1].qps -ge 900D -or
            -not [bool]$verdict.lastTwoWindowsDiagnosticOnly) {
        throw 'A diagnostic-only final 500-order window incorrectly blocked a qualified full segment.'
    }

    $fixture = Write-WarmupFixture (Join-Path $temp 'full-slow') 5000 $false $true
    $output = Join-Path $temp 'full-slow-output'
    & $reporter -RunId 'warmup-run' -GroupCode 'E-P1' `
        -ScenarioOrdersCsvPath $fixture.scenario -HttpEventsLogPath $fixture.http `
        -FocusedEventsCsvPath $fixture.focused -RequestResultsCsvPath $fixture.requests `
        -QueueSamplesCsvPath $fixture.queues -OutputDirectory $output
    $verdict = Get-Content -Raw -LiteralPath (
        Join-Path $output 'verdict.json') | ConvertFrom-Json
    if ($verdict.verdict -ne 'FAIL' -or
            [string]$verdict.performanceClassification -ne 'NOT_QUALIFIED' -or
            [double]$verdict.fullHttpQps -ge 900D) {
        throw 'A full 5K segment below the contract threshold was not rejected.'
    }

    $reusedApplicationLogRunId = 'application-run-warmup-E-P1-a1'
    $fixture = Write-WarmupFixture (
        Join-Path $temp 'reused-application') 5000 $false $false `
        $reusedApplicationLogRunId $true
    $output = Join-Path $temp 'reused-application-output'
    & $reporter -RunId 'warmup-run' -HttpLogRunId $reusedApplicationLogRunId `
        -GroupCode 'E-P1' -ScenarioOrdersCsvPath $fixture.scenario `
        -HttpEventsLogPath $fixture.http -FocusedEventsCsvPath $fixture.focused `
        -RequestResultsCsvPath $fixture.requests -QueueSamplesCsvPath $fixture.queues `
        -OutputDirectory $output -AllowEventsOutsideScenarioManifest
    $verdict = Get-Content -Raw -LiteralPath (
        Join-Path $output 'verdict.json') | ConvertFrom-Json
    if ($verdict.verdict -ne 'PASS' -or
            [string]$verdict.httpLogRunId -ne $reusedApplicationLogRunId -or
            [long]$verdict.http201Count -ne 5000L -or
            [long]$verdict.outsideScenarioHttpEventCount -ne 1L) {
        throw 'A reused-application HTTP evidence Run ID was not mapped to the warmup manifest.'
    }

    $output = Join-Path $temp 'missing-http-output'
    & $reporter -RunId 'warmup-run' -HttpLogRunId 'missing-application-run' `
        -GroupCode 'E-P1' -ScenarioOrdersCsvPath $fixture.scenario `
        -HttpEventsLogPath $fixture.http -FocusedEventsCsvPath $fixture.focused `
        -RequestResultsCsvPath $fixture.requests -QueueSamplesCsvPath $fixture.queues `
        -OutputDirectory $output
    $verdict = Get-Content -Raw -LiteralPath (
        Join-Path $output 'verdict.json') | ConvertFrom-Json
    if ($verdict.verdict -ne 'FAIL' -or [bool]$verdict.functionalPassed -or
            [long]$verdict.http201Count -ne 0L -or
            [string]$verdict.failureCode -ne 'WARMUP_HTTP_EVIDENCE_INCOMPLETE') {
        throw 'Missing HTTP evidence must produce a structured functional verdict instead of a Count exception.'
    }
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output 'PASS'
