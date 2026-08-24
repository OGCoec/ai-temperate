[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $SnapshotPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$snapshot = Get-Content -Raw -LiteralPath $SnapshotPath | ConvertFrom-Json
foreach ($queueName in @(
        'membership.payment.check.queue',
        'membership.closing.check.queue')) {
    $rows = @($snapshot.queues | Where-Object { $_.name -eq $queueName })
    if ($rows.Count -ne 1) {
        throw "Membership RabbitMQ queue is missing or duplicated: $queueName"
    }
    if ([int]$rows[0].consumers -ne 48) {
        throw "Membership RabbitMQ queue must have exactly forty-eight consumers: $queueName"
    }
}

Write-Output 'PASS'
