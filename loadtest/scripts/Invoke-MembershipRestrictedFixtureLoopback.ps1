[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('prepare', 'state', 'restore')]
    [string] $Operation,
    [string] $MainLoopbackUrl = 'http://127.0.0.1:6655',
    [Parameter(Mandatory = $true)]
    [string] $OutputFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$method = if ($Operation -eq 'state') { 'GET' } else { 'POST' }
$path = if ($Operation -eq 'state') { '' } else { "/$Operation" }
$response = Invoke-RestMethod `
    -Method $method `
    -Uri ($MainLoopbackUrl.TrimEnd('/') + '/internal/test/membership-payments/loadtest-control/restricted-fixtures' + $path) `
    -TimeoutSec 30
$users = @($response.users)
if ($users.Count -ne 4) { throw 'Restricted fixture response does not contain four fixed users.' }
$expected = @{
    '84758509811535872' = 'EDU'
    '84758866549673984' = 'EDU'
    '84759380653903872' = 'TEAM'
    '84760794662834176' = 'TEAM'
}
foreach ($user in $users) {
    if ($expected[[string]$user.userId] -ne [string]$user.tier) {
        throw 'Restricted fixture response contains an unexpected user or tier.'
    }
}
[ordered]@{
    verdict = 'PASS'
    operation = $Operation
    prepared = [bool]$response.prepared
    users = @($users | ForEach-Object {
        [ordered]@{ userId = [string]$_.userId; membershipTier = [string]$_.tier }
    })
    observedAt = [datetimeoffset]::UtcNow.ToString('O')
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputFile -Encoding UTF8
