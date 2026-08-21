[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $JtlPath,
    [Parameter(Mandatory = $true)] [string] $OutputPath
)

Set-StrictMode -Version Latest
$rows = @(Import-Csv -LiteralPath $JtlPath)
$summary = $rows | Group-Object label | ForEach-Object {
    [pscustomobject]@{
        label = $_.Name
        samples = $_.Count
        failures = @($_.Group | Where-Object { $_.success -ne 'true' }).Count
        p50Millis = @($_.Group | ForEach-Object { [int64]$_.elapsed } | Sort-Object)[[math]::Max(0, [math]::Floor($_.Count / 2) - 1)]
    }
}
$summary | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Encoding UTF8
