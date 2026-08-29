[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '..\Start-MembershipOrderCreateOptimizationRetest.ps1'
if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw "Optimization retest orchestrator is missing: $path"
}
$source = Get-Content -Raw -LiteralPath $path
$childPath = Join-Path $PSScriptRoot '..\Invoke-MembershipOptimizationRunChild.ps1'
if (-not (Test-Path -LiteralPath $childPath -PathType Leaf)) {
    throw "Optimization retest child wrapper is missing: $childPath"
}
foreach ($fragment in @(
        "-Stage 'E-P1_CANARY_5K'",
        "-Scale 'PERFORMANCE_40K'",
        "-Scale 'CAPACITY_80K'",
        '[int] $RedisWriteBatchSize = 64',
        '[int] $RedisWriteLaneCount = 6',
        '[int] $RedisWriteMaximumInflight = 384',
        'redisWriteBatchSize = $RedisWriteBatchSize',
        'redisWriteLaneCount = $RedisWriteLaneCount',
        'redisWriteMaximumInflight = $RedisWriteMaximumInflight',
        "-Stage 'PERFORMANCE_40K'",
        "-Stage 'CAPACITY_80K'",
        'directConcurrencyCanary = $DirectConcurrencyCanary',
        'expectedFormalSegmentCount = $ExpectedFormalSegmentCount',
        'goldenBaselineRunId = $goldenBaselineRunId',
        'goldenBaselineEvidenceRoot = $goldenBaselineEvidenceRoot',
        'previousScenarioOrdersCsvPath = @($previousScenarioPaths)',
        '$qpsRows.Count -eq $ExpectedFormalSegmentCount',
        "Join-Path `$childRoot 'eight-segment-sustainability-summary.json'",
        'maximumSegmentWallClockSeconds = $maximumWallClockSeconds',
        'layerWorstP95P99 = $layerWorstP95P99',
        'eightSegmentContinuity = $eightSegmentContinuity',
        '八段持续性：',
        '全部持续达标',
        '部分区段性能回退',
        'PASS_WITH_WARNINGS',
        '性能WARN',
        '未完成',
        '-ExpectedFormalSegmentCount 1',
        '-ExpectedFormalSegmentCount 8',
        'keepApplicationRunningAfterSuite = $KeepApplicationRunningAfterSuite',
        'reuseExistingApplication = $ReuseExistingApplication',
        'existingApplicationPid = if ($ReuseExistingApplication)',
        'existingApplicationDescriptorPath = if ($ReuseExistingApplication)',
        '-KeepApplicationRunningAfterSuite $true',
        '-ReuseExistingApplication $true',
        '40K充分预热正式测试',
        '80K充分预热容量测试',
        '合同门槛',
        '黄金基线复现',
        'run-state.json',
        'heartbeat.json',
        'run-ledger.json',
        'final-verdict.md',
        'PreviousScenarioOrdersCsvPath',
        'order-create-segment-qps.csv',
        'Invoke-MembershipOptimizationRunChild.ps1',
        'Start-Process',
        'currentChildPid',
        'Start-Sleep -Seconds 2',
        'source-fingerprint.json',
        'Assert-FrozenSource',
        'A91B1EEED2085748C5B615A290AFC913DE2623B7DE17EA3995690C880C9EBD45',
        'ConvertTo-Json -InputObject $Value',
        '$temporaryPath = "$Path.$PID.$([guid]::NewGuid().ToString(''N'')).partial"',
        '$stream.Flush($true)',
        '[IO.File]::Move($temporaryPath, $Path, $true)',
        '$retryClock = [Diagnostics.Stopwatch]::StartNew()',
        '$nativeError -in @(5, 32, 33)',
        'TEST_INVALID_EVIDENCE_PUBLICATION: JSON destination remained locked for 10 seconds',
        'function Read-JsonSnapshot',
        '[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete',
        '$heartbeat = Read-JsonSnapshot -Path $childHeartbeatPath',
        "if (`$Status -ne 'RUNNING')",
        'applicationPid = $publishedApplicationPid',
        'samplerPid = $publishedSamplerPid',
        'suitePid = $publishedSuitePid',
        'TEST_INVALID_EVIDENCE_PUBLICATION|Access to the path is denied|sharing violation|lock violation')) {
    if (-not $source.Contains($fragment)) {
        throw "Optimization retest contract is missing: $fragment"
    }
}
if ($source.Contains('Move-Item -LiteralPath $temporary -Destination $Path -Force')) {
    throw 'Optimization retest still uses a non-retrying Master state replacement.'
}
$childSource = Get-Content -Raw -LiteralPath $childPath
foreach ($fragment in @(
        'Start-MembershipSchedulerIndexHikariRetest.ps1',
        'PreviousScenarioOrdersCsvPath',
        'PreviousComparableRunRoot',
        'DirectConcurrencyCanary',
        'RedisWriteMaximumInflight',
        'KeepApplicationRunningAfterSuite',
        'ReuseExistingApplication',
        'ExistingApplicationPid',
        'ExistingApplicationDescriptorPath')) {
    if (-not $childSource.Contains($fragment)) {
        throw "Optimization child wrapper contract is missing: $fragment"
    }
}
if ($source.IndexOf("-Stage 'E-P1_CANARY_5K'") -gt
        $source.IndexOf("-Stage 'PERFORMANCE_40K'") -or
        $source.IndexOf("-Stage 'PERFORMANCE_40K'") -gt
        $source.IndexOf("-Stage 'CAPACITY_80K'")) {
    throw 'Master sequence is not Canary -> 40K -> 80K.'
}
$schedulerPath = Join-Path $PSScriptRoot '..\Start-MembershipSchedulerIndexHikariRetest.ps1'
$schedulerSource = Get-Content -Raw -LiteralPath $schedulerPath
foreach ($fragment in @(
        '[switch] $KeepApplicationRunningAfterSuite',
        '[switch] $ReuseExistingApplication',
        '[int] $ExistingApplicationPid',
        '$PreviousScenarioOrdersCsvPath = @($PreviousScenarioOrdersCsvPath)',
        '$unexpectedJava = @(if ($ReuseExistingApplication)',
        'reuseExistingApplication = [bool]$ReuseExistingApplication',
        '$keepApplicationOnExit = $true',
        'if (-not $keepApplicationOnExit)',
        'Validate-ReusedApplication',
        'timingLogRunId = [string]$application.runId')) {
    if (-not $schedulerSource.Contains($fragment)) {
        throw "Scheduler is missing same-process 40K/80K reuse contract: $fragment"
    }
}
if ($source.Contains('Remove-Item -Recurse') -or $source.Contains('Stop-Process -Name')) {
    throw 'Optimization retest contains a broad destructive operation.'
}

function Get-ScriptFunctionText([string] $ScriptPath, [string] $FunctionName) {
    $tokens = $null
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        $ScriptPath, [ref]$tokens, [ref]$parseErrors)
    if (@($parseErrors).Count -ne 0) {
        throw "Atomic JSON source does not parse: $ScriptPath"
    }
    $functionAst = $ast.Find({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $FunctionName
    }, $true)
    if ($null -eq $functionAst) {
        throw "Script function is missing: function=$FunctionName script=$ScriptPath"
    }
    return $functionAst.Extent.Text
}

function Assert-AtomicJsonWriterSurvivesTwoSecondReaderLock([string] $ScriptPath) {
    $writerFunctionText = Get-ScriptFunctionText `
        -ScriptPath $ScriptPath -FunctionName 'Write-AtomicJson'

    $testRoot = Join-Path ([IO.Path]::GetTempPath()) (
        'membership-atomic-json-lock-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $targetPath = Join-Path $testRoot 'state.json'
    $workerPath = Join-Path $testRoot 'writer.ps1'
    $stdoutPath = Join-Path $testRoot 'writer.stdout.log'
    $stderrPath = Join-Path $testRoot 'writer.stderr.log'
    $readerLock = $null
    $worker = $null
    try {
        '{}' | Set-Content -LiteralPath $targetPath -Encoding UTF8
        @"
[CmdletBinding()]
param([Parameter(Mandatory = `$true)][string] `$TargetPath)
Set-StrictMode -Version Latest
`$ErrorActionPreference = 'Stop'
$writerFunctionText
Write-AtomicJson -Path `$TargetPath -Value ([ordered]@{ verdict = 'PASS'; sequence = 1 })
"@ | Set-Content -LiteralPath $workerPath -Encoding UTF8

        # 读取句柄故意不共享删除权限，复现 Windows 覆盖移动曾触发的真实竞态。
        $readerLock = [IO.File]::Open(
            $targetPath, [IO.FileMode]::Open, [IO.FileAccess]::Read,
            [IO.FileShare]::Read)
        $worker = Start-Process -FilePath (Get-Process -Id $PID).Path `
            -ArgumentList @('-NoProfile', '-File', $workerPath, '-TargetPath', $targetPath) `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath `
            -WindowStyle Hidden -PassThru
        Start-Sleep -Seconds 2
        $worker.Refresh()
        if ($worker.HasExited) {
            $stderr = if (Test-Path -LiteralPath $stderrPath) {
                Get-Content -Raw -LiteralPath $stderrPath
            } else { '' }
            throw "Atomic JSON writer exited before the reader lock was released: script=$ScriptPath stderr=$stderr"
        }

        $readerLock.Dispose()
        $readerLock = $null
        if (-not $worker.WaitForExit(15000)) {
            throw "Atomic JSON writer did not recover after the reader lock was released: $ScriptPath"
        }
        if ($worker.ExitCode -ne 0) {
            throw "Atomic JSON writer failed after lock release: $(Get-Content -Raw -LiteralPath $stderrPath)"
        }
        $result = Get-Content -Raw -LiteralPath $targetPath | ConvertFrom-Json
        if ([string]$result.verdict -ne 'PASS' -or [int]$result.sequence -ne 1) {
            throw "Atomic JSON writer published an invalid snapshot after lock recovery: $ScriptPath"
        }
    } finally {
        if ($null -ne $readerLock) {
            $readerLock.Dispose()
        }
        if ($null -ne $worker) {
            $worker.Refresh()
            if (-not $worker.HasExited) {
                Stop-Process -Id $worker.Id -Force -ErrorAction SilentlyContinue
            }
            $worker.Dispose()
        }
        if (Test-Path -LiteralPath $testRoot -PathType Container) {
            Remove-Item -LiteralPath $testRoot -Recurse -Force
        }
    }
}

function Assert-AtomicJsonWriterTimesOutAfterTenSecondLock([string] $ScriptPath) {
    $writerFunctionText = Get-ScriptFunctionText `
        -ScriptPath $ScriptPath -FunctionName 'Write-AtomicJson'
    $testRoot = Join-Path ([IO.Path]::GetTempPath()) (
        'membership-atomic-json-timeout-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $targetPath = Join-Path $testRoot 'state.json'
    $workerPath = Join-Path $testRoot 'writer.ps1'
    $stdoutPath = Join-Path $testRoot 'writer.stdout.log'
    $stderrPath = Join-Path $testRoot 'writer.stderr.log'
    $readerLock = $null
    $worker = $null
    try {
        '{}' | Set-Content -LiteralPath $targetPath -Encoding UTF8
        @"
[CmdletBinding()]
param([Parameter(Mandatory = `$true)][string] `$TargetPath)
Set-StrictMode -Version Latest
`$ErrorActionPreference = 'Stop'
$writerFunctionText
Write-AtomicJson -Path `$TargetPath -Value ([ordered]@{ verdict = 'SHOULD_NOT_PUBLISH' })
"@ | Set-Content -LiteralPath $workerPath -Encoding UTF8
        $readerLock = [IO.File]::Open(
            $targetPath, [IO.FileMode]::Open, [IO.FileAccess]::Read,
            [IO.FileShare]::Read)
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $worker = Start-Process -FilePath (Get-Process -Id $PID).Path `
            -ArgumentList @('-NoProfile', '-File', $workerPath, '-TargetPath', $targetPath) `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath `
            -WindowStyle Hidden -PassThru
        if (-not $worker.WaitForExit(15000)) {
            throw 'Atomic JSON writer exceeded its bounded lock timeout.'
        }
        $clock.Stop()
        $stderr = Get-Content -Raw -LiteralPath $stderrPath
        if ($worker.ExitCode -eq 0 -or
                $stderr -notlike '*TEST_INVALID_EVIDENCE_PUBLICATION*') {
            throw "Atomic JSON writer did not classify a persistent lock as test-invalid: $stderr"
        }
        if ($clock.Elapsed.TotalSeconds -lt 9.5D -or
                $clock.Elapsed.TotalSeconds -gt 14D) {
            throw "Atomic JSON writer lock timeout was outside its bounded window: $($clock.Elapsed)"
        }
    } finally {
        if ($null -ne $readerLock) { $readerLock.Dispose() }
        if ($null -ne $worker) {
            $worker.Refresh()
            if (-not $worker.HasExited) {
                Stop-Process -Id $worker.Id -Force -ErrorAction SilentlyContinue
            }
            $worker.Dispose()
        }
        if (Test-Path -LiteralPath $testRoot -PathType Container) {
            Remove-Item -LiteralPath $testRoot -Recurse -Force
        }
    }
}

function Assert-AtomicJsonWriterSupportsConcurrentSafeReads([string] $ScriptPath) {
    $writerFunctionText = Get-ScriptFunctionText `
        -ScriptPath $ScriptPath -FunctionName 'Write-AtomicJson'
    $readerFunctionText = Get-ScriptFunctionText `
        -ScriptPath $ScriptPath -FunctionName 'Read-JsonSnapshot'
    $testRoot = Join-Path ([IO.Path]::GetTempPath()) (
        'membership-atomic-json-concurrent-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $targetPath = Join-Path $testRoot 'state.json'
    $workerPath = Join-Path $testRoot 'writer.ps1'
    $stdoutPath = Join-Path $testRoot 'writer.stdout.log'
    $stderrPath = Join-Path $testRoot 'writer.stderr.log'
    $worker = $null
    try {
        '{"sequence":0}' | Set-Content -LiteralPath $targetPath -Encoding UTF8
        @"
[CmdletBinding()]
param([Parameter(Mandatory = `$true)][string] `$TargetPath)
Set-StrictMode -Version Latest
`$ErrorActionPreference = 'Stop'
$writerFunctionText
foreach (`$sequence in 1..100) {
    Write-AtomicJson -Path `$TargetPath -Value ([ordered]@{ sequence = `$sequence })
}
"@ | Set-Content -LiteralPath $workerPath -Encoding UTF8
        Invoke-Expression $readerFunctionText
        $worker = Start-Process -FilePath (Get-Process -Id $PID).Path `
            -ArgumentList @('-NoProfile', '-File', $workerPath, '-TargetPath', $targetPath) `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath `
            -WindowStyle Hidden -PassThru
        $readCount = 0
        do {
            $snapshot = Read-JsonSnapshot -Path $targetPath
            if ([int]$snapshot.sequence -lt 0 -or [int]$snapshot.sequence -gt 100) {
                throw "Concurrent reader observed an invalid sequence: $($snapshot.sequence)"
            }
            $readCount += 1
            $worker.Refresh()
        } while (-not $worker.HasExited)
        if ($worker.ExitCode -ne 0) {
            throw "Concurrent atomic writer failed: $(Get-Content -Raw -LiteralPath $stderrPath)"
        }
        $finalSnapshot = Read-JsonSnapshot -Path $targetPath
        if ([int]$finalSnapshot.sequence -ne 100 -or $readCount -le 0) {
            throw "Concurrent atomic publication did not reach its complete final snapshot."
        }
        if (@(Get-ChildItem -LiteralPath $testRoot -Filter '*.partial').Count -ne 0) {
            throw 'Atomic JSON writer left a partial file after concurrent publication.'
        }
    } finally {
        if ($null -ne $worker) {
            $worker.Refresh()
            if (-not $worker.HasExited) {
                Stop-Process -Id $worker.Id -Force -ErrorAction SilentlyContinue
            }
            $worker.Dispose()
        }
        if (Test-Path -LiteralPath $testRoot -PathType Container) {
            Remove-Item -LiteralPath $testRoot -Recurse -Force
        }
    }
}

$suitePath = Join-Path $PSScriptRoot '..\Start-MembershipMillisecondBoundarySuite.ps1'
foreach ($atomicWriterPath in @($path, $schedulerPath, $suitePath)) {
    Assert-AtomicJsonWriterSurvivesTwoSecondReaderLock -ScriptPath $atomicWriterPath
}
Assert-AtomicJsonWriterTimesOutAfterTenSecondLock -ScriptPath $path
Assert-AtomicJsonWriterSupportsConcurrentSafeReads -ScriptPath $path
Write-Output 'PASS: ORDER_CREATE fixed 64x6/384 qualification and 80K capacity orchestration is complete.'
