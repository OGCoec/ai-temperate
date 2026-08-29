[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot '..\MembershipInterSegmentStability.psm1'
if (-not (Test-Path -LiteralPath $modulePath -PathType Leaf)) {
    throw 'The inter-segment stability module is missing.'
}
Import-Module $modulePath -Force

function Assert-Equal($Actual, $Expected, [string] $Message) {
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected Actual=$Actual"
    }
}

function New-TestRabbitObservation(
        [long] $PaymentReady = 0L,
        [long] $PaymentUnacked = 0L,
        [long] $ClosingReady = 0L,
        [long] $ClosingUnacked = 0L,
        [long] $PaymentDlqReady = 0L,
        [long] $ClosingDlqReady = 0L,
        [int] $PaymentConsumers = 48,
        [int] $ClosingConsumers = 48,
        [int] $PrefetchCount = 20,
        [bool] $ConsumersActive = $true,
        [int] $ChannelMax = 512,
        [switch] $AddUnexpectedNonEmptyQueue) {
    $connections = @([pscustomobject]@{ name = 'application'; channel_max = $ChannelMax })
    $queues = [Collections.Generic.List[object]]::new()
    [void]$queues.Add([pscustomobject]@{
        name = 'membership.payment.check.queue'
        consumers = $PaymentConsumers
        messages_ready = $PaymentReady
        messages_unacknowledged = $PaymentUnacked
    })
    [void]$queues.Add([pscustomobject]@{
        name = 'membership.closing.check.queue'
        consumers = $ClosingConsumers
        messages_ready = $ClosingReady
        messages_unacknowledged = $ClosingUnacked
    })
    [void]$queues.Add([pscustomobject]@{
        name = 'membership.payment.check.dlq'
        consumers = 0
        messages_ready = $PaymentDlqReady
        messages_unacknowledged = 0
    })
    [void]$queues.Add([pscustomobject]@{
        name = 'membership.closing.check.dlq'
        consumers = 0
        messages_ready = $ClosingDlqReady
        messages_unacknowledged = 0
    })
    if ($AddUnexpectedNonEmptyQueue) {
        [void]$queues.Add([pscustomobject]@{
            name = 'membership.unexpected.queue'
            consumers = 0
            messages_ready = 1
            messages_unacknowledged = 0
        })
    }

    $consumers = [Collections.Generic.List[object]]::new()
    foreach ($index in 1..$PaymentConsumers) {
        [void]$consumers.Add([pscustomobject]@{
            queue_name = 'membership.payment.check.queue'
            prefetch_count = $PrefetchCount
            active = $ConsumersActive
        })
    }
    foreach ($index in 1..$ClosingConsumers) {
        [void]$consumers.Add([pscustomobject]@{
            queue_name = 'membership.closing.check.queue'
            prefetch_count = $PrefetchCount
            active = $ConsumersActive
        })
    }

    return Get-MembershipInterSegmentRabbitObservation `
        -Connections @($connections) `
        -Queues @($queues) `
        -Consumers @($consumers)
}

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'membership-inter-segment-gate-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    $scenarioPath = Join-Path $temporaryRoot 'scenario-orders.csv'
    @(
        [pscustomobject]@{ hard_close_at = '2026-08-28T18:52:34.100000Z' },
        [pscustomobject]@{ hard_close_at = '2026-08-28T18:52:35.900000Z' },
        [pscustomobject]@{ hard_close_at = '2026-08-28T18:52:35.100000Z' }
    ) | Export-Csv -LiteralPath $scenarioPath -NoTypeInformation -Encoding UTF8
    $horizon = Get-MembershipLatestHardCloseAt -ScenarioOrdersCsvPath $scenarioPath
    Assert-Equal $horizon.ToString('O') '2026-08-28T18:52:35.9000000+00:00' `
        'The latest hard-close horizon was not selected.'

    $zero = New-TestRabbitObservation
    $deadline = $horizon.AddSeconds(240)
    $beforeHorizon = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon.AddSeconds(-1) `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $null `
        -RabbitObservation $zero
    Assert-Equal $beforeHorizon.decision 'WAIT' 'A pre-horizon zero sample must wait.'
    Assert-Equal $beforeHorizon.phase 'WAITING_FOR_HORIZON' `
        'A pre-horizon zero sample used the wrong phase.'
    if ($null -ne $beforeHorizon.quietSince) {
        throw 'Pre-horizon zero time must not count toward the quiet window.'
    }

    $delayed = New-TestRabbitObservation -ClosingReady 1
    $draining = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $beforeHorizon `
        -RabbitObservation $delayed
    Assert-Equal $draining.decision 'WAIT' `
        'A transient main-queue delayed message must not fail immediately.'
    Assert-Equal $draining.phase 'DRAINING' `
        'A transient main-queue delayed message must enter DRAINING.'

    $quietStart = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon.AddSeconds(2) `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $draining `
        -RabbitObservation $zero
    Assert-Equal $quietStart.phase 'QUIET' 'A drained queue must start the quiet window.'

    $activityAgain = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon.AddSeconds(62) `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $quietStart `
        -RabbitObservation $delayed
    if ($null -ne $activityAgain.quietSince) {
        throw 'New main-queue activity must reset the quiet window.'
    }
    $quietRestart = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon.AddSeconds(64) `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $activityAgain `
        -RabbitObservation $zero
    $passed = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon.AddSeconds(184) `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $quietRestart `
        -RabbitObservation $zero
    Assert-Equal $passed.decision 'PASS' `
        'The gate must pass after a full post-horizon quiet window.'

    $backlogStart = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $null `
        -RabbitObservation $delayed
    $backlogFailure = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon.AddSeconds(120) `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $backlogStart `
        -RabbitObservation $delayed
    Assert-Equal $backlogFailure.decision 'FAIL' `
        'A continuously non-empty main queue must fail.'
    Assert-Equal $backlogFailure.reasonCode 'RABBIT_MAIN_QUEUE_SUSTAINED_BACKLOG' `
        'A sustained backlog used the wrong reason code.'

    $dlq = New-TestRabbitObservation -PaymentDlqReady 1
    $dlqFailure = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $null `
        -RabbitObservation $dlq
    Assert-Equal $dlqFailure.reasonCode 'RABBIT_DLQ_NOT_EMPTY' `
        'A non-empty DLQ must fail immediately.'

    $unexpected = New-TestRabbitObservation -AddUnexpectedNonEmptyQueue
    Assert-Equal $unexpected.fatalReasonCode 'RABBIT_UNEXPECTED_QUEUE_NOT_EMPTY' `
        'An unexpected membership queue must not be tolerated.'

    $invalidConsumers = New-TestRabbitObservation -PaymentConsumers 47
    Assert-Equal $invalidConsumers.fatalReasonCode 'RABBIT_TOPOLOGY_INVALID' `
        'An invalid consumer count must remain a hard topology failure.'

    $invalidPrefetch = New-TestRabbitObservation -PrefetchCount 19
    Assert-Equal $invalidPrefetch.fatalReasonCode 'RABBIT_TOPOLOGY_INVALID' `
        'An invalid prefetch count must remain a hard topology failure.'
    $inactiveConsumers = New-TestRabbitObservation -ConsumersActive $false
    Assert-Equal $inactiveConsumers.fatalReasonCode 'RABBIT_TOPOLOGY_INVALID' `
        'An inactive consumer must remain a hard topology failure.'
    $invalidChannelMax = New-TestRabbitObservation -ChannelMax 511
    Assert-Equal $invalidChannelMax.fatalReasonCode 'RABBIT_TOPOLOGY_INVALID' `
        'A reduced channel_max must remain a hard topology failure.'

    $firstQuiet = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $null `
        -RabbitObservation $zero
    $lateActivity = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon.AddSeconds(119) `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $firstQuiet `
        -RabbitObservation $delayed
    $lastQuiet = Update-MembershipInterSegmentGateState `
        -SampledAt $horizon.AddSeconds(121) `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $lateActivity `
        -RabbitObservation $zero
    $timeoutFailure = Update-MembershipInterSegmentGateState `
        -SampledAt $deadline `
        -LatestHardCloseAt $horizon `
        -MaximumWaitDeadline $deadline `
        -QuietSeconds 120 `
        -CurrentState $lastQuiet `
        -RabbitObservation $zero
    Assert-Equal $timeoutFailure.reasonCode 'INTER_SEGMENT_STABILITY_TIMEOUT' `
        'Intermittent activity must not extend the bounded overall deadline.'

    $invalidScenarioPath = Join-Path $temporaryRoot 'invalid-scenario-orders.csv'
    [pscustomobject]@{ hard_close_at = 'not-a-time' } |
        Export-Csv -LiteralPath $invalidScenarioPath -NoTypeInformation -Encoding UTF8
    $invalidHorizonRejected = $false
    try {
        Get-MembershipLatestHardCloseAt -ScenarioOrdersCsvPath $invalidScenarioPath | Out-Null
    } catch {
        $invalidHorizonRejected = $_.Exception.Message.Contains('INVALID_SCENARIO_HORIZON')
    }
    if (-not $invalidHorizonRejected) {
        throw 'An invalid hard_close_at value must be rejected with a controlled reason code.'
    }
    $missingHorizonRejected = $false
    try {
        Get-MembershipLatestHardCloseAt `
            -ScenarioOrdersCsvPath (Join-Path $temporaryRoot 'missing.csv') | Out-Null
    } catch {
        $missingHorizonRejected = $_.Exception.Message.Contains('INVALID_SCENARIO_HORIZON')
    }
    if (-not $missingHorizonRejected) {
        throw 'Missing scenario evidence must be rejected with a controlled reason code.'
    }
} finally {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
}

Write-Output 'PASS: inter-segment delayed-message gate contracts are stable.'
