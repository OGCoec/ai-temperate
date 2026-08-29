[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ConfigurationPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedConfigurationPath = (Resolve-Path -LiteralPath $ConfigurationPath).Path
$configuration = Get-Content -Raw -LiteralPath $resolvedConfigurationPath |
    ConvertFrom-Json
if (-not [string]::Equals(
        [string]$configuration.fixedJarSha256,
        'A91B1EEED2085748C5B615A290AFC913DE2623B7DE17EA3995690C880C9EBD45',
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'TEST_INVALID_ARTIFACT: child configuration does not carry the locked JAR SHA-256.'
}
if ([string]$configuration.goldenBaselineRunId -ne
        'membership-order-create-redis64-lane6-doublewarmup-retest2-20260826-113048') {
    throw 'Child configuration does not carry the locked golden baseline Run ID.'
}
$arguments = @{
    RunId = [string]$configuration.runId
    PostgresMaxConnections = [int]$configuration.postgresMaxConnections
    HikariMaximumPoolSize = [int]$configuration.hikariMaximumPoolSize
    HikariMinimumIdle = [int]$configuration.hikariMinimumIdle
    MaximumNavicatConnections = [int]$configuration.maximumNavicatConnections
    RunScale = [string]$configuration.runScale
    RedisWriteBatchSize = [int]$configuration.redisWriteBatchSize
    RedisWriteLaneCount = [int]$configuration.redisWriteLaneCount
    RedisWriteMaximumInflight = [int]$configuration.redisWriteMaximumInflight
    ExpectedFormalSegmentCount = [int]$configuration.expectedFormalSegmentCount
    MasterRunId = [string]$configuration.masterRunId
    GoldenBaselineRunId = [string]$configuration.goldenBaselineRunId
    GoldenBaselineEvidenceRoot = [string]$configuration.goldenBaselineEvidenceRoot
    PostgresStabilitySeconds = [int]$configuration.postgresStabilitySeconds
    PreviousScenarioListIsAuthoritative = $true
}
if ([bool]$configuration.directConcurrencyCanary) {
    $arguments.DirectConcurrencyCanary = $true
}
if ([bool]$configuration.keepApplicationRunningAfterSuite) {
    $arguments.KeepApplicationRunningAfterSuite = $true
}
if ([bool]$configuration.reuseExistingApplication) {
    $arguments.ReuseExistingApplication = $true
    $arguments.ExistingApplicationPid = [int]$configuration.existingApplicationPid
    $arguments.ExistingApplicationDescriptorPath =
        [string]$configuration.existingApplicationDescriptorPath
}
$previousScenarios = @($configuration.previousScenarioOrdersCsvPath | ForEach-Object {
    [string]$_
})
if ($previousScenarios.Count -gt 0) {
    $arguments.PreviousScenarioOrdersCsvPath = $previousScenarios
}
$previousComparableRoot = [string]$configuration.previousComparableRunRoot
if (-not [string]::IsNullOrWhiteSpace($previousComparableRoot)) {
    $arguments.PreviousComparableRunRoot = $previousComparableRoot
}
$existingPostgresGate = [string]$configuration.existingPostgresStabilityGatePath
if (-not [string]::IsNullOrWhiteSpace($existingPostgresGate)) {
    $arguments.ExistingPostgresStabilityGatePath = $existingPostgresGate
}

try {
    & (Join-Path $PSScriptRoot 'Start-MembershipSchedulerIndexHikariRetest.ps1') `
        @arguments
} catch {
    Write-Error -ErrorRecord $_
    exit 1
}
