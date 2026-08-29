[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BeforeEvidence,
    [Parameter(Mandatory = $true)]
    [string] $AfterEvidence,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 10000)]
    [int] $ExpectedOrderCount,
    [ValidateRange(0, 1000)]
    [int] $ExpectedPaymentDlqDelta = 0,
    [Parameter(Mandatory = $true)]
    [string] $OutputFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$before = Get-Content -Raw -LiteralPath $BeforeEvidence | ConvertFrom-Json
$after = Get-Content -Raw -LiteralPath $AfterEvidence | ConvertFrom-Json
$failures = [System.Collections.Generic.List[string]]::new()

if ([string]$before.capture -ne 'BEFORE') { $failures.Add('invalid-before-capture') }
if ([string]$after.capture -ne 'AFTER') { $failures.Add('invalid-after-capture') }
if ([int]$after.orderCount -ne $ExpectedOrderCount) {
    $failures.Add("order-count:$($after.orderCount)/$ExpectedOrderCount")
}

foreach ($name in @(
    'callbackReadySize',
    'callbackProcessingSize',
    'dirtySize',
    'dirtyProcessingSize')) {
    if ([long]$after.redisQueues.$name -gt [long]$before.redisQueues.$name) {
        $failures.Add("redis-queue-exceeds-baseline:$name")
    }
}
if (@($after.redisArtifacts).Count -ne $ExpectedOrderCount) {
    $failures.Add("redis-artifact-count:$(@($after.redisArtifacts).Count)/$ExpectedOrderCount")
}
foreach ($artifact in @($after.redisArtifacts)) {
    if ([bool]$artifact.snapshotPresent) {
        $failures.Add("redis-snapshot-present:$($artifact.orderId)")
    }
    if ([bool]$artifact.callbackMarkerPresent) {
        $failures.Add("redis-marker-present:$($artifact.orderId)")
    }
}

$paymentQueue = 'membership.payment.check.queue'
$closingQueue = 'membership.closing.check.queue'
$paymentDlq = 'membership.payment.check.dlq'
$closingDlq = 'membership.closing.check.dlq'
foreach ($queueName in @($paymentQueue, $closingQueue, $paymentDlq, $closingDlq)) {
    $beforeRows = @($before.rabbitQueues | Where-Object { $_.name -eq $queueName })
    $afterRows = @($after.rabbitQueues | Where-Object { $_.name -eq $queueName })
    if ($beforeRows.Count -ne 1 -or $afterRows.Count -ne 1) {
        $failures.Add("rabbit-queue-missing-or-duplicate:$queueName")
        continue
    }
    if (-not [bool]$afterRows[0].durable `
        -or [string]$afterRows[0].type -ne 'quorum') {
        $failures.Add("rabbit-queue-not-durable-quorum:$queueName")
    }
    $readyDelta = [long]$afterRows[0].messages_ready `
        - [long]$beforeRows[0].messages_ready
    $unackedDelta = [long]$afterRows[0].messages_unacknowledged `
        - [long]$beforeRows[0].messages_unacknowledged
    $expectedReadyDelta = if ($queueName -eq $paymentDlq) {
        [long]$ExpectedPaymentDlqDelta
    } else {
        0L
    }
    if ($readyDelta -ne $expectedReadyDelta -or $unackedDelta -ne 0L) {
        $failures.Add(
            "rabbit-baseline-delta:$queueName:ready=$readyDelta:unacked=$unackedDelta")
    }
}

$result = [ordered]@{
    verdict = if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
    expectedOrderCount = $ExpectedOrderCount
    actualOrderCount = [int]$after.orderCount
    expectedPaymentDlqDelta = $ExpectedPaymentDlqDelta
    redisQueuesBefore = $before.redisQueues
    redisQueuesAfter = $after.redisQueues
    failures = @($failures)
    completedAt = [datetimeoffset]::UtcNow.ToString('O')
}
$parent = Split-Path -Parent $OutputFile
if (-not [string]::IsNullOrWhiteSpace($parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputFile -Encoding UTF8
if ($failures.Count -gt 0) {
    throw "Final membership payment infrastructure verification failed: $($failures -join ', ')"
}

