[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ConfigurationPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$configuration = Get-Content -Raw -LiteralPath $ConfigurationPath | ConvertFrom-Json
$suitePath = Join-Path $PSScriptRoot 'Start-MembershipMillisecondBoundarySuite.ps1'
$arguments = @{
    RunId = [string]$configuration.runId
    PreviousScenarioOrdersCsvPath = @($configuration.previousScenarioOrdersCsvPath)
    HostName = '127.0.0.1'
    Port = [int]$configuration.port
    Protocol = 'http'
    CreationConcurrency = 256
    HttpConcurrency = 256
    PaymentConcurrency = 56
    WarmupOrderCount = 0
    PrecheckSeconds = 0
    PostgresStabilitySeconds = [int]$configuration.postgresStabilitySeconds
    InterSegmentSeconds = 120
    PostgresMaxConnections = [int]$configuration.postgresMaxConnections
    HikariMaximumPoolSize = [int]$configuration.hikariMaximumPoolSize
    HikariMinimumIdle = [int]$configuration.hikariMinimumIdle
    MaximumNavicatConnections = [int]$configuration.maximumNavicatConnections
    RunScale = [string]$configuration.runScale
    RedisWriteBatchSize = [int]$configuration.redisWriteBatchSize
    RedisWriteLaneCount = [int]$configuration.redisWriteLaneCount
    RedisWriteMaximumInflight = [int]$configuration.redisWriteMaximumInflight
    GoldenBaselineRunId = [string]$configuration.goldenBaselineRunId
    GoldenBaselineEvidenceRoot = [string]$configuration.goldenBaselineEvidenceRoot
    TimingLogRunId = [string]$configuration.timingLogRunId
}

if ([bool]$configuration.stopAfterWarmupSequence) {
    $arguments.StopAfterWarmupSequence = $true
}

if ($configuration.PSObject.Properties.Name -contains 'existingPostgresStabilityGatePath' -and
        -not [string]::IsNullOrWhiteSpace([string]$configuration.existingPostgresStabilityGatePath)) {
    $arguments.ExistingPostgresStabilityGatePath =
        [string]$configuration.existingPostgresStabilityGatePath
}

if ($configuration.PSObject.Properties.Name -contains 'startGroupCode') {
    $arguments.StartGroupCode = [string]$configuration.startGroupCode
}
if ($configuration.PSObject.Properties.Name -contains 'skipInitialGates' -and
        [bool]$configuration.skipInitialGates) {
    $arguments.SkipInitialGates = $true
    $arguments.PrecheckSeconds = 0
    $arguments.WarmupOrderCount = 0
}

if ($configuration.PSObject.Properties.Name -contains 'directConcurrencyCanary' -and
        [bool]$configuration.directConcurrencyCanary) {
    $arguments.DirectConcurrencyCanary = $true
}

& $suitePath @arguments
