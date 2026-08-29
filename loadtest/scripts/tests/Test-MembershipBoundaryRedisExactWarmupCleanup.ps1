[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
. (Join-Path $repositoryRoot 'loadtest\scripts\MembershipBoundaryRedis.ps1')

function New-PublicUuidId {
    return [Convert]::ToBase64String([guid]::NewGuid().ToByteArray()).
        TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$orders = @(for ($index = 0; $index -lt 5000; $index += 1) { New-PublicUuidId })
$callbacks = @(for ($index = 0; $index -lt 5000; $index += 1) { New-PublicUuidId })
$script:scanCalled = $false
$script:existing = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($key in @(
        "ait:local:payment:membership-order:v2:snapshot:$($orders[0])",
        "ait:local:payment:membership-order:v2:callback:$($orders[0])",
        "ait:local:payment:provider-result:v2:status:$($orders[0])",
        "ait:local:payment:callback:v2:data:$($callbacks[0])",
        'ait:local:payment:membership-order:v2:snapshot:AgICAgICAgICAgICAgICAg')) {
    [void]$script:existing.Add($key)
}

function Invoke-MembershipBoundaryRedisCli {
    param(
        [Parameter(Mandatory = $true)] [string] $Container,
        [Parameter(Mandatory = $true)] [string[]] $Arguments
    )
    if ($Container -ne 'fake') { throw 'Unexpected fake Redis container.' }
    switch ($Arguments[0]) {
        '--scan' {
            $script:scanCalled = $true
            throw 'Exact warmup cleanup must not scan Redis.'
        }
        'ZCARD' { return '0' }
        'UNLINK' {
            $removed = 0
            foreach ($key in $Arguments[1..($Arguments.Count - 1)]) {
                if ($script:existing.Remove($key)) { $removed += 1 }
            }
            return [string]$removed
        }
        'EXISTS' {
            $count = 0
            foreach ($key in $Arguments[1..($Arguments.Count - 1)]) {
                if ($script:existing.Contains($key)) { $count += 1 }
            }
            return [string]$count
        }
        default { throw "Unexpected fake Redis command: $($Arguments -join ' ')" }
    }
}

$result = Remove-MembershipBoundaryRedisExactWarmupArtifacts `
    -Container 'fake' -OrderIds $orders -CallbackIds $callbacks -Environment 'local'
if ($script:scanCalled -or $result.expectedKeyCount -ne 40000 -or
        $result.unlinkedKeyCount -ne 4 -or $result.verifiedWorkSetCount -ne 8) {
    throw 'Exact warmup cleanup did not preserve its bounded non-SCAN contract.'
}
if (-not $script:existing.Contains(
        'ait:local:payment:membership-order:v2:snapshot:AgICAgICAgICAgICAgICAg')) {
    throw 'Exact warmup cleanup removed an unowned Redis key.'
}

Write-Output 'PASS'
