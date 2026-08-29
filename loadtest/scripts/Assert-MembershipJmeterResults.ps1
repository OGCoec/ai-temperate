[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Path,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 1000000)]
    [int] $ExpectedSampleCount,
    [Parameter(Mandatory = $true)]
    [string] $ExpectedSamplerName
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "JMeter result file is missing: $Path"
}
$rows = @(Import-Csv -LiteralPath $Path)
if ($rows.Count -ne $ExpectedSampleCount) {
    throw "JMeter result count is $($rows.Count); expected $ExpectedSampleCount."
}

$requiredColumns = @('label', 'success', 'responseCode', 'failureMessage')
$columns = @($rows[0].PSObject.Properties.Name)
foreach ($column in $requiredColumns) {
    if ($column -notin $columns) {
        throw "JMeter result file is missing required column: $column"
    }
}

foreach ($row in $rows) {
    if ([string]$row.label -ne $ExpectedSamplerName) {
        throw "Unexpected JMeter sampler: $($row.label)"
    }
    if ([string]$row.success -ine 'true') {
        throw "JMeter sampler reported success=false: $($row.label)"
    }
    if ([string]$row.responseCode -ne '200') {
        throw "JMeter sampler returned responseCode=$($row.responseCode): $($row.label)"
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$row.failureMessage)) {
        throw "JMeter sampler contains failureMessage: $($row.failureMessage)"
    }
}

Write-Output "PASS: validated $($rows.Count) JMeter sampler result(s)."
