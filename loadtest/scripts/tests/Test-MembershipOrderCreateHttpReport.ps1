[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$reporter = Join-Path $repositoryRoot `
    'loadtest\scripts\New-MembershipOrderCreateHttpReport.ps1'
$temp = Join-Path ([IO.Path]::GetTempPath()) `
    ('membership-order-http-report-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null

function New-OrderId([long] $Value) {
    $bytes = [byte[]]::new(16)
    [BitConverter]::GetBytes($Value).CopyTo($bytes, 8)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Write-Fixture(
        [string] $ScenarioPath,
        [string] $LogPath,
        [bool] $SlowFirstSegment,
        [long] $FirstSegmentWallMicros = 0L,
        [string] $HttpLogRunId = 'run-a') {
    $segments = @('E-P1','E-PR','E-A1','E-AR','H-P1','H-PR','H-A1','H-AR')
    $scenarioWriter = [IO.StreamWriter]::new($ScenarioPath, $false, [Text.UTF8Encoding]::new($false))
    $logWriter = [IO.StreamWriter]::new($LogPath, $false, [Text.UTF8Encoding]::new($false))
    try {
        $scenarioWriter.WriteLine('run_id,group_code,trace_id,order_id')
        $ordinal = 0L
        foreach ($segment in $segments) {
            $base = 1787660000000000L + $ordinal * 10000000L
            for ($index = 0; $index -lt 5000; $index++) {
                $trace = "trace-$segment-$($index.ToString('D5'))"
                $orderId = New-OrderId ($ordinal * 5000L + $index + 1L)
                $scenarioWriter.WriteLine("run-a,$segment,$trace,$orderId")
                $received = $base + $index
                $completed = $received + 100L
                if ($index -eq 4999) {
                    $completed = $base + 5000000L
                    if ($SlowFirstSegment -and $segment -eq 'E-P1') {
                        $completed += 1000L
                    }
                    if ($FirstSegmentWallMicros -gt 0L -and $segment -eq 'E-P1') {
                        $completed = $base + $FirstSegmentWallMicros
                    }
                }
                $logWriter.WriteLine(
                    "event=membership_order_create_http_completed v=1 r=$HttpLogRunId sg=$segment tr=$trace recv=$received done=$completed dur=$($completed-$received) status=201 committed=true")
            }
            $ordinal++
        }
    } finally {
        $scenarioWriter.Dispose()
        $logWriter.Dispose()
    }
}

function Write-DirectConcurrencyFixture(
        [string] $ScenarioPath,
        [string] $LogPath,
        [string] $RequestPath,
        [bool] $PaymentOverlaps) {
    $scenarioWriter = [IO.StreamWriter]::new($ScenarioPath, $false, [Text.UTF8Encoding]::new($false))
    $logWriter = [IO.StreamWriter]::new($LogPath, $false, [Text.UTF8Encoding]::new($false))
    $requestWriter = [IO.StreamWriter]::new($RequestPath, $false, [Text.UTF8Encoding]::new($false))
    try {
        $scenarioWriter.WriteLine('run_id,group_code,trace_id,order_id')
        $requestWriter.WriteLine('run_id,wave_code,group_code,user_id,operation,http_status,success,error_type,transport_attempts,started_at,completed_at')
        $base = 1787660000000000L
        $wallMicros = 7142857L
        $durationMicros = 285715L
        $latestReceived = $wallMicros - $durationMicros
        $paymentBase = if ($PaymentOverlaps) { 1000000L } else { 8000000L }
        for ($index = 0; $index -lt 5000; $index++) {
            $trace = "trace-E-P1-$($index.ToString('D5'))"
            $orderId = New-OrderId ($index + 1L)
            $scenarioWriter.WriteLine("run-a,E-P1,$trace,$orderId")
            $received = $base + [long][Math]::Floor($index * $latestReceived / 4999D)
            $completed = $received + $durationMicros
            $logWriter.WriteLine(
                "event=membership_order_create_http_completed v=1 r=run-a sg=E-P1 tr=$trace recv=$received done=$completed dur=$durationMicros status=201 committed=true")
            $paymentStartedMicros = $base + $paymentBase + $index
            $paymentCompletedMicros = $paymentStartedMicros + 1000L
            $paymentStarted = [datetimeoffset]::FromUnixTimeMilliseconds(
                [long][Math]::Floor($paymentStartedMicros / 1000D)).UtcDateTime.AddTicks(
                    ($paymentStartedMicros % 1000L) * 10L).ToString(
                        "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'", [Globalization.CultureInfo]::InvariantCulture)
            $paymentCompleted = [datetimeoffset]::FromUnixTimeMilliseconds(
                [long][Math]::Floor($paymentCompletedMicros / 1000D)).UtcDateTime.AddTicks(
                    ($paymentCompletedMicros % 1000L) * 10L).ToString(
                        "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'", [Globalization.CultureInfo]::InvariantCulture)
            $requestWriter.WriteLine(
                "run-a,E-PRE,E-P1,$($index+1),START_PAYMENT,201,true,,1,$paymentStarted,$paymentCompleted")
        }
    } finally {
        $scenarioWriter.Dispose()
        $logWriter.Dispose()
        $requestWriter.Dispose()
    }
}

try {
    $scenario = Join-Path $temp 'scenario.csv'
    $passLog = Join-Path $temp 'pass.log'
    Write-Fixture $scenario $passLog $false
    $passOutput = Join-Path $temp 'pass'
    & $reporter -LogPath $passLog -RunId 'run-a' `
        -ScenarioOrdersCsvPath $scenario -OutputDirectory $passOutput
    $pass = Get-Content -Raw -LiteralPath `
        (Join-Path $passOutput 'order-create-http-verdict.json') | ConvertFrom-Json
    if ($pass.verdict -ne 'PASS' -or $pass.segments.Count -ne 8 -or
            @($pass.segments | Where-Object {
                    $_.http201Count -ne 5000 -or $_.wallClockMicros -ne 5000000 -or
                    $_.qps -ne 1000 -or $_.verdict -ne 'PASS'
                }).Count -ne 0) {
        throw 'Exact 5.000 second HTTP segment was not accepted.'
    }
    foreach ($name in @(
            'order-create-http-events.log',
            'order-create-segment-qps.csv',
            'order-create-segment-latency.json')) {
        if (-not (Test-Path -LiteralPath (Join-Path $passOutput $name) -PathType Leaf)) {
            throw "HTTP report artifact is missing: $name"
        }
    }

    $failLog = Join-Path $temp 'fail.log'
    Write-Fixture $scenario $failLog $true
    $failOutput = Join-Path $temp 'fail'
    & $reporter -LogPath $failLog -RunId 'run-a' `
        -ScenarioOrdersCsvPath $scenario -OutputDirectory $failOutput
    $fail = Get-Content -Raw -LiteralPath `
        (Join-Path $failOutput 'order-create-http-verdict.json') | ConvertFrom-Json
    $slow = @($fail.segments | Where-Object segment -eq 'E-P1')[0]
    if ($fail.verdict -ne 'FAIL' -or $slow.wallClockMicros -ne 5001000 -or
            $slow.verdict -ne 'FAIL') {
        throw 'Exact 5.001 second HTTP segment was not rejected.'
    }

    $roundingLog = Join-Path $temp 'rounding-edge.log'
    Write-Fixture $scenario $roundingLog $false 5555556L
    $roundingOutput = Join-Path $temp 'rounding-edge'
    & $reporter -LogPath $roundingLog -RunId 'run-a' `
        -ScenarioOrdersCsvPath $scenario -OutputDirectory $roundingOutput `
        -MinimumQps 900 -MaximumWallClockSeconds 5.556
    $rounding = Get-Content -Raw -LiteralPath `
        (Join-Path $roundingOutput 'order-create-http-verdict.json') | ConvertFrom-Json
    $roundingSegment = @($rounding.segments | Where-Object segment -eq 'E-P1')[0]
    if ($rounding.verdict -ne 'FAIL' -or $roundingSegment.verdict -ne 'FAIL' -or
            [double]$roundingSegment.qpsGateValueUnrounded -ge 900D -or
            -not [bool]$rounding.evaluatedUsingUnroundedQps) {
        throw 'A QPS value that only rounds up to 900 was incorrectly accepted.'
    }

    $directScenario = Join-Path $temp 'direct-scenario.csv'
    $directLog = Join-Path $temp 'direct.log'
    $directRequests = Join-Path $temp 'direct-requests.csv'
    Write-DirectConcurrencyFixture $directScenario $directLog $directRequests $true
    $directOutput = Join-Path $temp 'direct'
    & $reporter -LogPath $directLog -RunId 'run-a' `
        -ScenarioOrdersCsvPath $directScenario -RequestResultsCsvPath $directRequests `
        -OutputDirectory $directOutput -MinimumQps 700 `
        -MaximumWallClockSeconds 7.143 -MinimumEffectiveConcurrency 200 `
        -RequirePaymentOverlap
    $direct = Get-Content -Raw -LiteralPath `
        (Join-Path $directOutput 'order-create-http-verdict.json') | ConvertFrom-Json
    $directSegment = @($direct.segments)[0]
    if ($direct.verdict -ne 'PASS' -or $direct.segments.Count -ne 1 -or
            $directSegment.wallClockMicros -ne 7142857 -or
            [double]$directSegment.qps -lt 700D -or
            [double]$directSegment.effectiveCreateConcurrency -lt 200D -or
            -not [bool]$directSegment.paymentOverlap) {
        throw 'Direct concurrency 200 / 700 QPS canary was not accepted.'
    }
    if (-not (Test-Path -LiteralPath (
            Join-Path $directOutput 'order-create-payment-concurrency-curve.csv') -PathType Leaf)) {
        throw 'Direct concurrency report did not emit the create/payment activity curve.'
    }

    $nonOverlapLog = Join-Path $temp 'non-overlap.log'
    $nonOverlapRequests = Join-Path $temp 'non-overlap-requests.csv'
    Write-DirectConcurrencyFixture $directScenario $nonOverlapLog $nonOverlapRequests $false
    $nonOverlapOutput = Join-Path $temp 'non-overlap'
    & $reporter -LogPath $nonOverlapLog -RunId 'run-a' `
        -ScenarioOrdersCsvPath $directScenario -RequestResultsCsvPath $nonOverlapRequests `
        -OutputDirectory $nonOverlapOutput -MinimumQps 700 `
        -MaximumWallClockSeconds 7.143 -MinimumEffectiveConcurrency 200 `
        -RequirePaymentOverlap
    $nonOverlap = Get-Content -Raw -LiteralPath `
        (Join-Path $nonOverlapOutput 'order-create-http-verdict.json') | ConvertFrom-Json
    if ($nonOverlap.verdict -ne 'FAIL' -or [bool]$nonOverlap.segments[0].paymentOverlap) {
        throw 'A payment stream starting after the last create response was not rejected.'
    }

    $reusedLog = Join-Path $temp 'reused-application.log'
    Write-Fixture $scenario $reusedLog $false 0L 'application-run'
    [IO.File]::AppendAllText(
        $reusedLog,
        "event=membership_order_create_http_completed v=1 r=application-run sg=E-P1 tr=trace-from-prior-stage recv=1 done=2 dur=1 status=201 committed=true`n",
        [Text.UTF8Encoding]::new($false))
    $reusedOutput = Join-Path $temp 'reused-application'
    & $reporter -LogPath $reusedLog -RunId 'run-a' -HttpLogRunId 'application-run' `
        -ScenarioOrdersCsvPath $scenario -OutputDirectory $reusedOutput `
        -AllowEventsOutsideManifest
    $reused = Get-Content -Raw -LiteralPath `
        (Join-Path $reusedOutput 'order-create-http-verdict.json') | ConvertFrom-Json
    if ($reused.verdict -ne 'PASS' -or
            [string]$reused.httpLogRunId -ne 'application-run' -or
            [long]$reused.observedTraceCount -ne 40000L -or
            [long]$reused.outsideManifestEventCount -ne 1L) {
        throw 'Formal HTTP reporting did not map a reused application log Run ID to the scenario traces.'
    }
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output 'PASS'
