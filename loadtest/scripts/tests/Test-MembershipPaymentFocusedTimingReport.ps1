[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$reporterPath = Join-Path $repositoryRoot `
    'loadtest\scripts\New-MembershipPaymentFocusedTimingReport.ps1'
$tempDirectory = Join-Path ([IO.Path]::GetTempPath()) `
    ('membership-focused-report-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDirectory -Force | Out-Null

try {
    $orderOne = 'AQEBAQEBAQEBAQEBAQEBAQ'
    # Base64URL 区分大小写；报告关联键不得把仅大小写不同的两个订单误判为重复。
    $orderTwo = 'AQEBAQEBAQEBAQEBAQEBaq'
    $scenarioPath = Join-Path $tempDirectory 'scenario-orders-all.csv'
    @(
        [pscustomobject]@{ run_id = 'run-a'; group_code = 'E-P1'; order_id = $orderOne },
        [pscustomobject]@{ run_id = 'run-a'; group_code = 'H-AR'; order_id = $orderTwo }
    ) | Export-Csv -LiteralPath $scenarioPath -NoTypeInformation -Encoding UTF8

    $logPath = Join-Path $tempDirectory 'membership-payment-state-machine.log'
    @(
        "event=membership_payment_operation_completed runId=old-run operation=ORDER_CREATE traceId=old messageId=unavailable orderIdB64=$orderOne flow=none stageIndex=unavailable outcome=SUCCESS completedAtEpochMs=1 totalMs=9999.000 redisOrderWriteMs=9000.000 redisProviderWriteMs=0.000 redisOrderMs=0.000 redisTransitionMs=0.000 otherRedisMs=0.000 rabbitPublishConfirmMs=0.000 markerMs=0.000 dbMs=0.000 ackMs=0.000 ackAction=none barRefundMs=0.000 errorClass=none",
        "event=membership_payment_operation_completed runId=run-a operation=ORDER_CREATE traceId=warmup messageId=unavailable orderIdB64=BAQEBAQEBAQEBAQEBAQEBA flow=none stageIndex=unavailable outcome=SUCCESS completedAtEpochMs=5 totalMs=5000.000 redisOrderWriteMs=4900.000 redisProviderWriteMs=0.000 redisOrderMs=0.000 redisTransitionMs=0.000 otherRedisMs=0.000 rabbitPublishConfirmMs=0.000 markerMs=0.000 dbMs=100.000 ackMs=0.000 ackAction=none barRefundMs=0.000 errorClass=none",
        "event=membership_payment_operation_completed v=2 r=app-run op=ORDER_CREATE oid=$orderOne out=SUCCESS end=10 t=10.125 app=2.125 row=4.000 rwp=1.000 rwq=2.000 rwb=3.000 rwe=4.000 rwd=5.000 rwsz=64 rwl=5 ro=1.000 or=2.000 rpc=1.000 rps=6.000 rcw=7.000 rpsz=1 db=2.000 dbt=8.000",
        "event=membership_payment_operation_completed v=2 r=app-run op=ORDER_CREATE out=FAILED end=15 t=0.125 app=0.125 err=MembershipPaymentException dc=0",
        "event=membership_payment_operation_completed v=2 r=app-run op=ORDER_CREATE oid=$orderTwo out=SUCCESS end=20 t=20.375 app=4.375 row=8.000 ro=2.000 or=4.000 rpc=2.000 db=4.000",
        "event=membership_payment_operation_completed v=2 r=app-run op=PAYMENT_ATTEMPT oid=$orderOne out=SUCCESS end=30 t=30.500 app=13.500 row=3.000 rpw=7.000 ro=1.000 or=2.000 rpc=1.000 db=3.000",
        "event=membership_payment_operation_completed v=2 r=app-run op=PAYMENT_ATTEMPT oid=$orderTwo out=FAILED end=40 t=40.625 app=12.625 row=4.000 rpw=8.000 ro=2.000 or=3.000 rpc=2.000 db=4.000 br=1.000 err=IllegalStateException tr=t4",
        "event=membership_payment_operation_completed v=2 r=app-run op=RABBIT_PENDING oid=$orderOne out=ACKED end=50 t=1500.250 app=0.250 ro=1496.000 rt=2.000 mk=1.000 ack=1.000 aa=ACK tr=t5 mid=m1 fl=PENDING si=8 dc=0 qa=1000 do=0",
        "event=membership_payment_operation_completed v=2 r=app-run op=RABBIT_CLOSING oid=$orderTwo out=NACKED end=60 t=6.750 app=1.750 ro=1.000 rt=2.000 mk=1.000 ack=1.000 aa=NACK tr=t6 mid=m2 fl=CLOSING si=4 dc=0"
    ) | Set-Content -LiteralPath $logPath -Encoding UTF8

    # 正式报告生成时应用仍会持续写入日志，测试必须覆盖 Windows 的文件共享语义。
    $activeLogWriter = [IO.FileStream]::new(
        $logPath,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Write,
        [IO.FileShare]::ReadWrite)
    try {
        & $reporterPath -LogPath $logPath -RunId 'run-a' -LogRunId 'app-run' `
            -ScenarioOrdersCsvPath $scenarioPath -OutputDirectory $tempDirectory `
            -MinimumCompletedAtEpochMs 10
    } finally {
        $activeLogWriter.Dispose()
    }

    $eventsPath = Join-Path $tempDirectory 'membership-payment-focused-events.csv'
    $summaryPath = Join-Path $tempDirectory `
        'membership-payment-focused-operation-summary.csv'
    $topPath = Join-Path $tempDirectory 'membership-payment-focused-top-100.csv'
    $diagnosticsPath = Join-Path $tempDirectory `
        'membership-payment-slow-failure-diagnostics.csv'
    $jsonPath = Join-Path $tempDirectory 'membership-payment-focused-report.json'
    $markdownPath = Join-Path $tempDirectory 'membership-payment-focused-report.md'
    foreach ($path in @(
            $eventsPath, $summaryPath, $topPath, $diagnosticsPath,
            $jsonPath, $markdownPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Focused timing artifact was not created: $path"
        }
    }

    $events = @(Import-Csv -LiteralPath $eventsPath)
    if ($events.Count -ne 4 -or
        @($events | Where-Object { $_.runId -ne 'run-a' }).Count -ne 0) {
        throw 'Focused event report did not isolate the requested Run ID.'
    }
    if (@($events | Where-Object { $_.applicationMs -eq '' }).Count -ne 0) {
        throw 'Compact v2 applicationMs was not expanded into the focused CSV.'
    }
    $expanded = @($events | Where-Object {
            $_.operation -eq 'ORDER_CREATE' -and $_.orderIdB64 -eq $orderOne
        })[0]
    if ($expanded.redisWritePermitWaitMs -ne '1.000' -or
        $expanded.redisWriteQueueWaitMs -ne '2.000' -or
        $expanded.redisPipelineBatchWaitMs -ne '3.000' -or
        $expanded.redisPipelineExecuteMs -ne '4.000' -or
        $expanded.redisWriteDispatchMs -ne '5.000' -or
        $expanded.redisPipelineBatchSize -ne '64' -or
        $expanded.redisPipelineLane -ne '5' -or
        $expanded.rabbitPublishSubmitMs -ne '6.000' -or
        $expanded.rabbitConfirmWaitMs -ne '7.000' -or
        $expanded.rabbitSubmissionSize -ne '1' -or
        $expanded.dbTransactionMs -ne '8.000') {
        throw 'Redis, Rabbit or database breakdown fields were not expanded.'
    }
    $summary = @(Import-Csv -LiteralPath $summaryPath)
    if ($summary.Count -ne 2) {
        throw 'Focused operation summary must contain exactly two HTTP operation rows.'
    }
    $create = @($summary | Where-Object operation -eq 'ORDER_CREATE')[0]
    if ($create.attemptCount -ne '2' -or $create.uniqueOrderCount -ne '2' -or
        $create.averageMs -ne '15.250' -or $create.p95Ms -ne '20.375' -or
        $create.redisOrderWriteP99Ms -ne '8.000' -or
        $create.redisWritePermitWaitP99Ms -ne '1.000' -or
        $create.rabbitConfirmWaitP99Ms -ne '7.000' -or
        $create.dbTransactionP99Ms -ne '8.000') {
        throw 'Focused ORDER_CREATE statistics are incorrect.'
    }
    $top = @(Import-Csv -LiteralPath $topPath)
    if ($top.Count -ne 4 -or $top[0].totalMs -ne '40.625') {
        throw 'Focused Top 100 ordering is incorrect.'
    }
    $diagnostics = @(Import-Csv -LiteralPath $diagnosticsPath)
    if ($diagnostics.Count -ne 3 -or
        @($diagnostics | Where-Object {
                $_.operation -eq 'ORDER_CREATE' -and $_.groupCode -eq 'unavailable'
            }).Count -ne 1 -or
        @($diagnostics | Where-Object { $_.operation -eq 'RABBIT_PENDING' }).Count -ne 1 -or
        @($diagnostics | Where-Object { $_.operation -eq 'RABBIT_CLOSING' }).Count -ne 1) {
        throw 'Slow, failed or NACK diagnostics were not separated from the HTTP main sample.'
    }
    $report = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json
    if ($report.runId -ne 'run-a' -or $report.logRunId -ne 'app-run' -or
        $report.eventCount -ne 4 -or
        $report.diagnosticEventCount -ne 3 -or $report.operations.Count -ne 2 -or
        $report.minimumCompletedAtEpochMs -ne 10) {
        throw 'Focused JSON report is incomplete.'
    }
    $markdown = Get-Content -LiteralPath $markdownPath -Raw
    if ($markdown -notmatch 'HTTP 主链路' -or
        $markdown -notmatch '11968.412' -or
        $markdown -notmatch '其他操作只保留') {
        throw 'Focused Markdown report is missing its measurement boundary or old anchors.'
    }

    $invalidPath = Join-Path $tempDirectory 'invalid.log'
    "event=membership_payment_operation_completed runId=run-a operation=ORDER_GET traceId=t7 messageId=unavailable orderIdB64=$orderOne flow=none stageIndex=unavailable outcome=SUCCESS completedAtEpochMs=70 totalMs=1.000 redisOrderWriteMs=0.000 redisProviderWriteMs=0.000 redisOrderMs=1.000 redisTransitionMs=0.000 otherRedisMs=0.000 rabbitPublishConfirmMs=0.000 markerMs=0.000 dbMs=0.000 ackMs=0.000 ackAction=none barRefundMs=0.000 errorClass=none" |
        Set-Content -LiteralPath $invalidPath -Encoding UTF8
    $rejected = $false
    try {
        & $reporterPath -LogPath $invalidPath -RunId 'run-a' `
            -ScenarioOrdersCsvPath $scenarioPath -OutputDirectory $tempDirectory
    } catch {
        $rejected = $_.Exception.Message -match 'unexpected fast success outside the two forced operations'
    }
    if (-not $rejected) {
        throw 'Focused reporter accepted an unexpected fast success outside the forced HTTP operations.'
    }
} finally {
    Remove-Item -LiteralPath $tempDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output 'PASS'
