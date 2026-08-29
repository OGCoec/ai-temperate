$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$reporter = Join-Path $PSScriptRoot '..\New-MembershipPaymentTimingReport.ps1'
if (-not (Test-Path -LiteralPath $reporter -PathType Leaf)) {
    throw 'Membership payment timing reporter is missing.'
}

$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
    'membership-payment-timing-report-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDirectory | Out-Null
try {
    $logPath = Join-Path $tempDirectory 'membership-payment-state-machine.log'
    $scenarioPath = Join-Path $tempDirectory 'scenario-orders-all.csv'
    $outputDirectory = Join-Path $tempDirectory 'logs'
    $orderOne = 'AQIDBAUGBwgJCgsMDQ4PEA'
    $orderTwo = 'ERITFBUWFxgZGhscHR4fIA'

    @(
        ('event=membership_payment_operation_completed runId=run-a completedAtEpochMs=1000 traceId=t1 messageId=m1 orderIdB64=' + $orderOne + ' orderRef=o1 operation=RABBIT_PENDING flow=PENDING stageIndex=0 deliveryCount=1 fromStatus=PENDING toStatus=PENDING totalMs=10.125 applicationMs=5.000 redisOrderMs=2.000 dbMs=3.000 markerMs=1.000 barQueryMs=0.000 barCloseMs=0.000 rabbitPublishConfirmMs=2.000 ackMs=0.100 queueAgeMs=10000.000 deliveryOverdueMs=0.000 outcome=ACKED errorClass=none'),
        ('event=membership_payment_operation_completed runId=run-a completedAtEpochMs=2000 traceId=t2 messageId=m2 orderIdB64=' + $orderTwo + ' orderRef=o2 operation=RABBIT_PENDING flow=PENDING stageIndex=0 deliveryCount=1 fromStatus=PENDING toStatus=PENDING totalMs=20.375 applicationMs=6.000 redisOrderMs=4.000 dbMs=5.000 markerMs=2.000 barQueryMs=0.000 barCloseMs=0.000 rabbitPublishConfirmMs=3.000 ackMs=0.200 queueAgeMs=10025.000 deliveryOverdueMs=25.000 outcome=ACKED errorClass=none'),
        ('event=membership_payment_operation_completed runId=run-a completedAtEpochMs=3000 traceId=t3 messageId=m3 orderIdB64=' + $orderOne + ' orderRef=o1 operation=RABBIT_PENDING flow=PENDING stageIndex=8 deliveryCount=1 fromStatus=PENDING toStatus=PENDING totalMs=40.000 applicationMs=10.000 redisOrderMs=4.000 dbMs=6.000 markerMs=2.000 barQueryMs=14.000 barCloseMs=0.000 rabbitPublishConfirmMs=0.000 ackMs=0.300 queueAgeMs=10000.000 deliveryOverdueMs=0.000 outcome=NACKED errorClass=java.lang.IllegalStateException'),
        ('event=membership_payment_operation_completed runId=run-a completedAtEpochMs=4000 traceId=t4 messageId=m3 orderIdB64=' + $orderOne + ' orderRef=o1 operation=RABBIT_PENDING flow=PENDING stageIndex=8 deliveryCount=2 fromStatus=PENDING toStatus=CLOSING totalMs=30.000 applicationMs=8.000 redisOrderMs=3.000 dbMs=5.000 markerMs=1.000 barQueryMs=10.000 barCloseMs=0.000 rabbitPublishConfirmMs=0.000 ackMs=0.250 queueAgeMs=10000.000 deliveryOverdueMs=0.000 outcome=ACKED errorClass=none'),
        ('event=membership_payment_operation_completed runId=run-a completedAtEpochMs=5000 traceId=t5 messageId=m4 orderIdB64=' + $orderTwo + ' orderRef=o2 operation=RABBIT_CLOSING flow=CLOSING stageIndex=4 deliveryCount=1 fromStatus=CLOSING toStatus=CLOSED totalMs=50.000 applicationMs=12.000 redisOrderMs=3.000 dbMs=5.000 markerMs=1.000 barQueryMs=0.000 barCloseMs=20.000 rabbitPublishConfirmMs=0.000 ackMs=0.400 queueAgeMs=5000.000 deliveryOverdueMs=0.000 outcome=ACKED errorClass=none'),
        ('event=membership_payment_operation_completed runId=run-b completedAtEpochMs=6000 traceId=old messageId=old orderIdB64=' + $orderTwo + ' orderRef=old operation=RABBIT_PENDING flow=PENDING stageIndex=0 deliveryCount=1 fromStatus=PENDING toStatus=PENDING totalMs=999.000 applicationMs=999.000 redisOrderMs=999.000 dbMs=999.000 markerMs=999.000 barQueryMs=0.000 barCloseMs=0.000 rabbitPublishConfirmMs=0.000 ackMs=0.100 queueAgeMs=0.000 deliveryOverdueMs=0.000 outcome=ACKED errorClass=none')
    ) | Set-Content -LiteralPath $logPath -Encoding UTF8

    @(
        [pscustomobject]@{
            group_code = 'E-P1'
            user_id = '70000000000000000'
            order_id = $orderOne
        },
        [pscustomobject]@{
            group_code = 'E-P1'
            user_id = '70000000000000001'
            order_id = $orderTwo
        }
    ) | Export-Csv -LiteralPath $scenarioPath -NoTypeInformation -Encoding UTF8

    & $reporter `
        -LogPath $logPath `
        -RunId 'run-a' `
        -ScenarioOrdersCsvPath $scenarioPath `
        -OutputDirectory $outputDirectory `
        -TopSlowCount 2 | Out-Null

    $jsonPath = Join-Path $outputDirectory 'membership-payment-timing-report.json'
    $detailsPath = Join-Path $outputDirectory 'membership-payment-order-stage-details.csv'
    $matrixPath = Join-Path $outputDirectory 'membership-payment-order-stage-matrix.csv'
    $summaryPath = Join-Path $outputDirectory 'membership-payment-stage-summary.csv'
    $markdownPath = Join-Path $outputDirectory 'membership-payment-test-report.md'

    foreach ($expectedPath in @($jsonPath, $detailsPath, $matrixPath, $summaryPath, $markdownPath)) {
        if (-not (Test-Path -LiteralPath $expectedPath -PathType Leaf)) {
            throw ('Expected timing artifact was not created: {0}' -f $expectedPath)
        }
    }

    $report = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json
    if ($report.runId -ne 'run-a' -or $report.sampleCount -ne 5) {
        throw 'Run ID filtering or timing report sample count is incorrect.'
    }

    $details = @(Import-Csv -LiteralPath $detailsPath)
    if ($details.Count -ne 5 -or @($details | Where-Object { $_.runId -ne 'run-a' }).Count -ne 0) {
        throw 'Timing details did not preserve only the requested Run ID.'
    }

    $summary = @(Import-Csv -LiteralPath $summaryPath)
    if ($summary.Count -ne 14) {
        throw 'The timing stage summary must always contain all 14 state-machine rows.'
    }
    $pendingZero = @($summary | Where-Object { $_.stage -eq 'PENDING 0' })[0]
    if ($pendingZero.attemptCount -ne '2' -or $pendingZero.uniqueOrderCount -ne '2' -or
        $pendingZero.averageMs -ne '15.250' -or $pendingZero.p95Ms -ne '20.375') {
        throw 'PENDING 0 statistics or fixed three-decimal formatting is incorrect.'
    }
    $pendingEight = @($summary | Where-Object { $_.stage -eq 'PENDING 8' })[0]
    if ($pendingEight.attemptCount -ne '2' -or $pendingEight.uniqueOrderCount -ne '1' -or
        $pendingEight.ackCount -ne '1' -or $pendingEight.nackCount -ne '1') {
        throw 'PENDING 8 retry and ACK/NACK aggregation is incorrect.'
    }

    $matrix = @(Import-Csv -LiteralPath $matrixPath)
    if ($matrix.Count -ne 2) {
        throw 'The order timing matrix does not contain every scenario order.'
    }
    $orderOneMatrix = @($matrix | Where-Object { $_.orderIdB64 -eq $orderOne })[0]
    if ($orderOneMatrix.PENDING_8_ATTEMPTS -ne '2' -or
        $orderOneMatrix.PENDING_8_OUTCOME -ne 'ACKED' -or
        $orderOneMatrix.CLOSING_0_OUTCOME -ne 'NOT_EXECUTED') {
        throw 'Order timing retry or NOT_EXECUTED matrix semantics are incorrect.'
    }

    $markdown = Get-Content -LiteralPath $markdownPath -Raw
    if ($markdown -notmatch 'PENDING 0' -or $markdown -notmatch 'P95' -or
        $markdown -notmatch '全量详细日志') {
        throw 'The Markdown timing report is incomplete.'
    }
} finally {
    Remove-Item -LiteralPath $tempDirectory -Recurse -Force
}

Write-Output 'PASS'
