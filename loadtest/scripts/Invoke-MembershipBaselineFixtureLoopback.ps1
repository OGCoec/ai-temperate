[CmdletBinding()]
param(
    [ValidateSet('prepare', 'state')]
    [string] $Operation = 'state',
    [string] $MainLoopbackUrl = 'http://127.0.0.1:6655',
    [Parameter(Mandatory = $true)]
    [string] $OutputFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$method = if ($Operation -eq 'state') { 'GET' } else { 'POST' }
$path = if ($Operation -eq 'state') { '' } else { '/prepare' }
$response = Invoke-RestMethod `
    -Method $method `
    -Uri ($MainLoopbackUrl.TrimEnd('/') +
        '/internal/test/membership-payments/loadtest-control/baseline-fixtures' + $path) `
    -TimeoutSec 30
$users = @($response.users)
if ($users.Count -ne 16) {
    throw 'Baseline fixture response does not contain sixteen fixed users.'
}
if (-not [bool]$response.prepared `
    -or @($users | Where-Object { [string]$_.tier -ne 'FREE' }).Count -ne 0) {
    throw 'Baseline fixture response is not a complete FREE baseline.'
}
[ordered]@{
    verdict = 'PASS'
    operation = $Operation
    prepared = $true
    users = @($users | ForEach-Object {
        [ordered]@{ userId = [string]$_.userId; membershipTier = [string]$_.tier }
    })
    observedAt = [datetimeoffset]::UtcNow.ToString('O')
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputFile -Encoding UTF8
