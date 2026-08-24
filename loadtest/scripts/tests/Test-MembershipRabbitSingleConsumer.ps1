$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$validator = Join-Path $PSScriptRoot '..\Test-MembershipRabbitSingleConsumer.ps1'
if (-not (Test-Path -LiteralPath $validator -PathType Leaf)) {
    throw 'Rabbit single-consumer validator is missing.'
}

$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
    'membership-rabbit-consumer-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDirectory | Out-Null
try {
    $valid = Join-Path $tempDirectory 'valid.json'
    $duplicate = Join-Path $tempDirectory 'duplicate.json'
    $missing = Join-Path $tempDirectory 'missing.json'
    @{ queues = @(
        @{ name = 'membership.payment.check.queue'; consumers = 48 },
        @{ name = 'membership.closing.check.queue'; consumers = 48 }
    ) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $valid -Encoding UTF8
    @{ queues = @(
        @{ name = 'membership.payment.check.queue'; consumers = 47 },
        @{ name = 'membership.closing.check.queue'; consumers = 48 }
    ) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $duplicate -Encoding UTF8
    @{ queues = @(
        @{ name = 'membership.payment.check.queue'; consumers = 48 }
    ) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $missing -Encoding UTF8

    & pwsh -NoProfile -File $validator -SnapshotPath $valid
    if ($LASTEXITCODE -ne 0) {
        throw 'A valid forty-eight-consumer snapshot was rejected.'
    }

    & pwsh -NoProfile -File $validator -SnapshotPath $duplicate 2>$null
    if ($LASTEXITCODE -eq 0) {
        throw 'A duplicate membership consumer snapshot was accepted.'
    }

    & pwsh -NoProfile -File $validator -SnapshotPath $missing 2>$null
    if ($LASTEXITCODE -eq 0) {
        throw 'A missing membership queue snapshot was accepted.'
    }
} finally {
    Remove-Item -LiteralPath $tempDirectory -Recurse -Force
}

Write-Output 'PASS'
