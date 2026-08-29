[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
. (Join-Path $repositoryRoot 'loadtest\scripts\MembershipBoundaryRedis.ps1')

$ownedOrder = 'AQEBAQEBAQEBAQEBAQEBAQ'
$otherOrder = 'AgICAgICAgICAgICAgICAg'
$ownedCallback = 'AwMDAwMDAwMDAwMDAwMDAw'
$otherCallback = 'BAQEBAQEBAQEBAQEBAQEBA'
$script:fakeKeys = [Collections.Generic.HashSet[string]]::new(
    [StringComparer]::Ordinal)
foreach ($key in @(
        "ait:local:payment:membership-order:v2:snapshot:$ownedOrder",
        "ait:local:payment:membership-order:v2:callback:$ownedOrder",
        "ait:local:payment:provider-result:v2:status:$ownedOrder",
        "ait:local:payment:callback:v2:data:$ownedCallback",
        "ait:local:payment:membership-order:v2:snapshot:$otherOrder",
        "ait:local:payment:callback:v2:data:$otherCallback",
        'ait:local:payment:callback:v2:ready:all',
        'ait:local:payment:callback:v2:processing:all',
        'ait:local:payment:order-persist:v2:dirty:all',
        'ait:local:payment:order-persist:v2:processing:all')) {
    [void]$script:fakeKeys.Add($key)
}
$script:fakeZsets = @{
    'ait:local:payment:callback:v2:ready:all' =
        [Collections.Generic.List[string]]@($ownedCallback, $otherCallback)
    'ait:local:payment:callback:v2:processing:all' =
        [Collections.Generic.List[string]]@($ownedCallback)
    'ait:local:payment:order-persist:v2:dirty:all' =
        [Collections.Generic.List[string]]@("$ownedOrder#2", "$otherOrder#1")
    'ait:local:payment:order-persist:v2:processing:all' =
        [Collections.Generic.List[string]]@("$ownedOrder#3")
}

function Invoke-MembershipBoundaryRedisCli {
    param(
        [Parameter(Mandatory = $true)] [string] $Container,
        [Parameter(Mandatory = $true)] [string[]] $Arguments
    )
    if ($Container -ne 'fake') { throw 'Unexpected fake Redis container.' }
    switch ($Arguments[0]) {
        '--scan' {
            $pattern = $Arguments[2]
            return @($script:fakeKeys | Where-Object { $_ -like $pattern })
        }
        'UNLINK' {
            $removed = 0
            foreach ($key in $Arguments[1..($Arguments.Count - 1)]) {
                if ($script:fakeKeys.Remove($key)) { $removed += 1 }
            }
            return [string]$removed
        }
        'ZRANGE' {
            return @($script:fakeZsets[$Arguments[1]])
        }
        'ZREM' {
            $key = $Arguments[1]
            $removed = 0
            foreach ($member in $Arguments[2..($Arguments.Count - 1)]) {
                if ($script:fakeZsets[$key].Remove($member)) { $removed += 1 }
            }
            return [string]$removed
        }
        default { throw "Unexpected fake Redis command: $($Arguments -join ' ')" }
    }
}

$result = Remove-MembershipBoundaryRedisOrderArtifacts `
    -Container 'fake' -OrderIds @($ownedOrder) -CallbackIds @($ownedCallback)
if ($result.unlinkedKeyCount -ne 4 -or
        $result.removedCallbackWorkMemberCount -ne 2 -or
        $result.removedOrderWorkMemberCount -ne 2) {
    throw 'Exact Redis cleanup returned incorrect removal counts.'
}
foreach ($key in @(
        "ait:local:payment:membership-order:v2:snapshot:$otherOrder",
        "ait:local:payment:callback:v2:data:$otherCallback")) {
    if (-not $script:fakeKeys.Contains($key)) {
        throw "Exact Redis cleanup removed an unowned key: $key"
    }
}
if ($script:fakeZsets['ait:local:payment:callback:v2:ready:all'] -notcontains
        $otherCallback -or
        $script:fakeZsets['ait:local:payment:order-persist:v2:dirty:all'] -notcontains
        "$otherOrder#1") {
    throw 'Exact Redis cleanup removed an unowned work-set member.'
}

Write-Output 'PASS'
