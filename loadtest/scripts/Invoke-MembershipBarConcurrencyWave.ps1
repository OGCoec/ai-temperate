[CmdletBinding()]
param(
    [string] $MainBaseUrl = 'https://niko000o.site',
    [string] $UsersCsv = 'loadtest/local/loadtest-users.csv',
    [Parameter(Mandatory = $true)]
    [string] $OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$usersPath = if ([IO.Path]::IsPathRooted($UsersCsv)) { $UsersCsv } else { Join-Path $repoRoot $UsersCsv }
$rows = @(Import-Csv -LiteralPath $usersPath)
if ($rows.Count -ne 16 -or @($rows | Where-Object { [string]::IsNullOrWhiteSpace($_.accessToken) }).Count -gt 0) {
    throw 'BAR concurrency requires sixteen non-empty approved tokens.'
}
$uri = [uri]$MainBaseUrl
$port = if ($uri.IsDefaultPort) { if ($uri.Scheme -eq 'https') { 443 } else { 80 } } else { $uri.Port }
$output = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
} else {
    Join-Path $repoRoot $OutputDirectory
}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$jtl = Join-Path $output 'membership-bar-order-concurrency.jtl'
$log = Join-Path $output 'jmeter.log'
$report = Join-Path $output 'html-report'
$orders = Join-Path $output 'scenario-orders.csv'

$arguments = @(
    '-n',
    '-t', (Join-Path $repoRoot 'loadtest/jmeter/membership-order-concurrency.jmx'),
    '-l', $jtl,
    '-j', $log,
    '-e',
    '-o', $report,
    '-JMODE=loadtest-bar',
    '-JTHREADS=3',
    '-JRAMP_UP=1',
    "-JPROTOCOL=$($uri.Scheme)",
    "-JHOST=$($uri.Host)",
    "-JPORT=$port",
    "-JUSERS_CSV=$usersPath",
    "-JSCENARIO_ORDERS_CSV=$orders",
    "-JORDER_CONCURRENCY_CASES_CSV=$(Join-Path $repoRoot 'loadtest/input/membership-order-concurrency-bar-cases.csv')",
    "-JORDER_CONCURRENCY_SCRIPT=$(Join-Path $repoRoot 'loadtest/scripts/jmeter/membership-order-concurrency.groovy')",
    '-Jjmeter.save.saveservice.output_format=csv',
    '-Jjmeter.save.saveservice.print_field_names=true'
)
& jmeter @arguments
if ($LASTEXITCODE -ne 0) { throw 'BAR order concurrency JMeter execution failed.' }
if (-not (Test-Path -LiteralPath $orders)) { throw 'BAR concurrency evidence CSV is missing.' }
$evidence = @(Import-Csv -LiteralPath $orders)
if ($evidence.Count -ne 12) { throw "BAR concurrency expected twelve orders but found $($evidence.Count)." }
if (@($evidence | Where-Object { [int]$_.concurrency -gt 50 }).Count -gt 0) {
    throw 'BAR concurrency evidence exceeded the shared C50 boundary.'
}
$closingRows = @($evidence | Where-Object {
    [int]$_.closing_create_409 -eq 1 -and [string]$_.expected_status -eq 'CLOSED'
})
if ($closingRows.Count -ne 3) {
    throw 'BAR concurrency must prove one real CLOSING second-order rejection per concurrency tier.'
}
if (@($evidence | Where-Object {
    ([string]$_.expected_status -eq 'CLOSED' -and [int]$_.closing_create_409 -ne 1) `
        -or ([string]$_.expected_status -eq 'CANCELLED' -and [int]$_.closing_create_409 -ne 0)
}).Count -gt 0) {
    throw 'BAR concurrency closing evidence is internally inconsistent.'
}
[ordered]@{
    verdict = 'PASS'
    actualOrders = $evidence.Count
    maximumConcurrency = 50
    closingConflictOrders = $closingRows.Count
    jtl = $jtl
    report = $report
    completedAt = [datetimeoffset]::UtcNow.ToString('O')
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'verdict.json') -Encoding UTF8
