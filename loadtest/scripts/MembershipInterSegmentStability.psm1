Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:PaymentQueueName = 'membership.payment.check.queue'
$script:ClosingQueueName = 'membership.closing.check.queue'
$script:PaymentDlqName = 'membership.payment.check.dlq'
$script:ClosingDlqName = 'membership.closing.check.dlq'

function Get-MembershipLatestHardCloseAt {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $ScenarioOrdersCsvPath
    )

    if (-not (Test-Path -LiteralPath $ScenarioOrdersCsvPath -PathType Leaf)) {
        throw "INVALID_SCENARIO_HORIZON: Scenario order evidence is missing: $ScenarioOrdersCsvPath"
    }
    $rows = @(Import-Csv -LiteralPath $ScenarioOrdersCsvPath)
    if ($rows.Count -eq 0) {
        throw "INVALID_SCENARIO_HORIZON: Scenario order evidence is empty: $ScenarioOrdersCsvPath"
    }

    [datetimeoffset] $latest = [datetimeoffset]::MinValue
    foreach ($row in $rows) {
        $hardCloseProperty = $row.PSObject.Properties['hard_close_at']
        $raw = if ($null -eq $hardCloseProperty) { '' } else {
            [string]$hardCloseProperty.Value
        }
        [datetimeoffset] $parsed = [datetimeoffset]::MinValue
        $valid = -not [string]::IsNullOrWhiteSpace($raw) -and
            [datetimeoffset]::TryParse(
                $raw,
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind,
                [ref]$parsed)
        if (-not $valid) {
            throw "INVALID_SCENARIO_HORIZON: Invalid hard_close_at value: $raw"
        }
        if ($parsed -gt $latest) {
            $latest = $parsed
        }
    }
    return $latest
}

function Get-MembershipInterSegmentRabbitObservation {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [object[]] $Connections,
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [object[]] $Queues,
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [object[]] $Consumers
    )

    $paymentRows = @($Queues | Where-Object name -eq $script:PaymentQueueName)
    $closingRows = @($Queues | Where-Object name -eq $script:ClosingQueueName)
    $paymentDlqRows = @($Queues | Where-Object name -eq $script:PaymentDlqName)
    $closingDlqRows = @($Queues | Where-Object name -eq $script:ClosingDlqName)
    $payment = if ($paymentRows.Count -eq 1) { $paymentRows[0] } else { $null }
    $closing = if ($closingRows.Count -eq 1) { $closingRows[0] } else { $null }
    $paymentDlq = if ($paymentDlqRows.Count -eq 1) { $paymentDlqRows[0] } else { $null }
    $closingDlq = if ($closingDlqRows.Count -eq 1) { $closingDlqRows[0] } else { $null }

    $observation = [ordered]@{
        paymentReady = if ($null -eq $payment) { 0L } else { [long]$payment.messages_ready }
        paymentUnacked = if ($null -eq $payment) { 0L } else { [long]$payment.messages_unacknowledged }
        closingReady = if ($null -eq $closing) { 0L } else { [long]$closing.messages_ready }
        closingUnacked = if ($null -eq $closing) { 0L } else { [long]$closing.messages_unacknowledged }
        paymentDlqReady = if ($null -eq $paymentDlq) { 0L } else { [long]$paymentDlq.messages_ready }
        paymentDlqUnacked = if ($null -eq $paymentDlq) { 0L } else { [long]$paymentDlq.messages_unacknowledged }
        closingDlqReady = if ($null -eq $closingDlq) { 0L } else { [long]$closingDlq.messages_ready }
        closingDlqUnacked = if ($null -eq $closingDlq) { 0L } else { [long]$closingDlq.messages_unacknowledged }
        mainNonEmpty = $false
        fatalReasonCode = ''
        fatalReasonMessage = ''
    }

    if ($Connections.Count -eq 0) {
        $observation.fatalReasonCode = 'RABBIT_INSPECTION_FAILED'
        $observation.fatalReasonMessage = 'RabbitMQ returned no connection-capacity evidence.'
        return [pscustomobject]$observation
    }
    $invalidConnections = @($Connections | Where-Object { [int]$_.channel_max -lt 512 })
    if ($invalidConnections.Count -ne 0) {
        $observation.fatalReasonCode = 'RABBIT_TOPOLOGY_INVALID'
        $observation.fatalReasonMessage = 'RabbitMQ negotiated channel_max is below 512.'
        return [pscustomobject]$observation
    }

    if ($paymentRows.Count -ne 1 -or $closingRows.Count -ne 1 -or
            $paymentDlqRows.Count -ne 1 -or $closingDlqRows.Count -ne 1) {
        $observation.fatalReasonCode = 'RABBIT_TOPOLOGY_INVALID'
        $observation.fatalReasonMessage =
            'RabbitMQ membership main queues and DLQs must each exist exactly once.'
        return [pscustomobject]$observation
    }
    foreach ($name in @($script:PaymentQueueName, $script:ClosingQueueName)) {
        $queueRow = (@($Queues | Where-Object name -eq $name))[0]
        $consumerRows = @($Consumers | Where-Object queue_name -eq $name)
        if ([int]$queueRow.consumers -ne 48 -or $consumerRows.Count -ne 48 -or
                @($consumerRows | Where-Object {
                    [int]$_.prefetch_count -ne 20 -or -not [bool]$_.active
                }).Count -ne 0) {
            $observation.fatalReasonCode = 'RABBIT_TOPOLOGY_INVALID'
            $observation.fatalReasonMessage =
                "RabbitMQ queue must have 48 active consumers with prefetch 20: $name"
            return [pscustomobject]$observation
        }
    }

    if ($observation.paymentDlqReady -ne 0L -or
            $observation.paymentDlqUnacked -ne 0L -or
            $observation.closingDlqReady -ne 0L -or
            $observation.closingDlqUnacked -ne 0L) {
        $observation.fatalReasonCode = 'RABBIT_DLQ_NOT_EMPTY'
        $observation.fatalReasonMessage =
            "RabbitMQ membership DLQ is not empty: payment=$($observation.paymentDlqReady)/$($observation.paymentDlqUnacked), closing=$($observation.closingDlqReady)/$($observation.closingDlqUnacked)"
        return [pscustomobject]$observation
    }

    $expectedNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in @(
            $script:PaymentQueueName,
            $script:ClosingQueueName,
            $script:PaymentDlqName,
            $script:ClosingDlqName)) {
        [void]$expectedNames.Add($name)
    }
    $unexpectedNonEmpty = @($Queues | Where-Object {
        $_.name -like 'membership.*' -and
        -not $expectedNames.Contains([string]$_.name) -and
        ([long]$_.messages_ready -ne 0L -or
            [long]$_.messages_unacknowledged -ne 0L)
    })
    if ($unexpectedNonEmpty.Count -ne 0) {
        $details = @($unexpectedNonEmpty | ForEach-Object {
            "$($_.name):ready=$($_.messages_ready),unacked=$($_.messages_unacknowledged)"
        }) -join ', '
        $observation.fatalReasonCode = 'RABBIT_UNEXPECTED_QUEUE_NOT_EMPTY'
        $observation.fatalReasonMessage =
            "Unexpected RabbitMQ membership queue is not empty: $details"
        return [pscustomobject]$observation
    }

    $observation.mainNonEmpty =
        $observation.paymentReady -ne 0L -or
        $observation.paymentUnacked -ne 0L -or
        $observation.closingReady -ne 0L -or
        $observation.closingUnacked -ne 0L
    return [pscustomobject]$observation
}

function Update-MembershipInterSegmentGateState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [datetimeoffset] $SampledAt,
        [Parameter(Mandatory = $true)]
        [datetimeoffset] $LatestHardCloseAt,
        [Parameter(Mandatory = $true)]
        [datetimeoffset] $MaximumWaitDeadline,
        [Parameter(Mandatory = $true)]
        [ValidateRange(60, 600)]
        [int] $QuietSeconds,
        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [psobject] $CurrentState,
        [Parameter(Mandatory = $true)]
        [psobject] $RabbitObservation
    )

    $quietSince = $null
    $nonEmptySince = $null
    if ($null -ne $CurrentState) {
        if ($null -ne $CurrentState.quietSince) {
            $quietSince = [datetimeoffset]$CurrentState.quietSince
        }
        if ($null -ne $CurrentState.nonEmptySince) {
            $nonEmptySince = [datetimeoffset]$CurrentState.nonEmptySince
        }
    }

    if (-not [string]::IsNullOrWhiteSpace([string]$RabbitObservation.fatalReasonCode)) {
        return [pscustomobject][ordered]@{
            decision = 'FAIL'
            phase = 'FAILED'
            quietSince = $null
            nonEmptySince = $nonEmptySince
            quietElapsedSeconds = 0L
            nonEmptyElapsedSeconds = 0L
            reasonCode = [string]$RabbitObservation.fatalReasonCode
            reasonMessage = [string]$RabbitObservation.fatalReasonMessage
        }
    }

    if ([bool]$RabbitObservation.mainNonEmpty) {
        if ($null -eq $nonEmptySince) {
            $nonEmptySince = $SampledAt
        }
        $nonEmptyElapsed = [long][Math]::Max(
            0D, [Math]::Floor(($SampledAt - $nonEmptySince).TotalSeconds))
        if ($nonEmptyElapsed -ge $QuietSeconds) {
            return [pscustomobject][ordered]@{
                decision = 'FAIL'
                phase = 'DRAINING'
                quietSince = $null
                nonEmptySince = $nonEmptySince
                quietElapsedSeconds = 0L
                nonEmptyElapsedSeconds = $nonEmptyElapsed
                reasonCode = 'RABBIT_MAIN_QUEUE_SUSTAINED_BACKLOG'
                reasonMessage =
                    "RabbitMQ main queues remained non-empty for ${nonEmptyElapsed}s."
            }
        }
        if ($SampledAt -ge $MaximumWaitDeadline) {
            return [pscustomobject][ordered]@{
                decision = 'FAIL'
                phase = 'DRAINING'
                quietSince = $null
                nonEmptySince = $nonEmptySince
                quietElapsedSeconds = 0L
                nonEmptyElapsedSeconds = $nonEmptyElapsed
                reasonCode = 'INTER_SEGMENT_STABILITY_TIMEOUT'
                reasonMessage = 'The RabbitMQ inter-segment stability deadline expired.'
            }
        }
        return [pscustomobject][ordered]@{
            decision = 'WAIT'
            phase = 'DRAINING'
            quietSince = $null
            nonEmptySince = $nonEmptySince
            quietElapsedSeconds = 0L
            nonEmptyElapsedSeconds = $nonEmptyElapsed
            reasonCode = ''
            reasonMessage = 'A bounded RabbitMQ main-queue drain is in progress.'
        }
    }

    if ($SampledAt -lt $LatestHardCloseAt) {
        return [pscustomobject][ordered]@{
            decision = 'WAIT'
            phase = 'WAITING_FOR_HORIZON'
            quietSince = $null
            nonEmptySince = $null
            quietElapsedSeconds = 0L
            nonEmptyElapsedSeconds = 0L
            reasonCode = ''
            reasonMessage = 'Waiting for the latest hard-close horizon.'
        }
    }

    if ($null -eq $quietSince) {
        $quietSince = $SampledAt
    }
    $quietElapsed = [long][Math]::Max(
        0D, [Math]::Floor(($SampledAt - $quietSince).TotalSeconds))
    if ($quietElapsed -ge $QuietSeconds) {
        return [pscustomobject][ordered]@{
            decision = 'PASS'
            phase = 'QUIET'
            quietSince = $quietSince
            nonEmptySince = $null
            quietElapsedSeconds = $quietElapsed
            nonEmptyElapsedSeconds = 0L
            reasonCode = ''
            reasonMessage = "RabbitMQ remained empty for ${quietElapsed}s after the hard-close horizon."
        }
    }
    if ($SampledAt -ge $MaximumWaitDeadline) {
        return [pscustomobject][ordered]@{
            decision = 'FAIL'
            phase = 'QUIET'
            quietSince = $quietSince
            nonEmptySince = $null
            quietElapsedSeconds = $quietElapsed
            nonEmptyElapsedSeconds = 0L
            reasonCode = 'INTER_SEGMENT_STABILITY_TIMEOUT'
            reasonMessage = 'The full post-horizon quiet window was not reached before the deadline.'
        }
    }
    return [pscustomobject][ordered]@{
        decision = 'WAIT'
        phase = 'QUIET'
        quietSince = $quietSince
        nonEmptySince = $null
        quietElapsedSeconds = $quietElapsed
        nonEmptyElapsedSeconds = 0L
        reasonCode = ''
        reasonMessage = 'The post-horizon quiet window is still accumulating.'
    }
}

Export-ModuleMember -Function @(
    'Get-MembershipLatestHardCloseAt',
    'Get-MembershipInterSegmentRabbitObservation',
    'Update-MembershipInterSegmentGateState')
