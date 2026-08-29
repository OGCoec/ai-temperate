[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $JarPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^https://')]
    [string] $SandboxVideoUrl,
    [string] $UsersCsv = 'loadtest/local/loadtest-users.csv',
    [string] $PostgresUrl = '',
    [string] $OutputFile = 'loadtest/local/bar-operator-evidence/quota-first-use.json',
    [ValidateRange(1024, 65535)]
    [int] $Port = 18080,
    [ValidateRange(30, 600)]
    [int] $StartupTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$usersPath = if ([IO.Path]::IsPathRooted($UsersCsv)) {
    $UsersCsv
} else {
    Join-Path $repoRoot $UsersCsv
}
$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputFile)) {
    $OutputFile
} else {
    Join-Path $repoRoot $OutputFile
}
$outputParent = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
$runtimeRoot = Join-Path $outputParent (
        'w16-loopback-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
$stdoutPath = Join-Path $runtimeRoot 'application.out.log'
$stderrPath = Join-Path $runtimeRoot 'application.err.log'
$proxyKeyBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($proxyKeyBytes)
$proxyKey = [Convert]::ToBase64String($proxyKeyBytes)
$loopbackBase = "http://127.0.0.1:$Port"
$stubBase = $loopbackBase `
        + '/internal/test/membership-payments/inference-stub'
$instanceId = 'w16-' + [guid]::NewGuid().ToString('N')
$jarSha256 = (Get-FileHash -LiteralPath $resolvedJar -Algorithm SHA256).Hash.ToLowerInvariant()
$process = $null
$stdoutTask = $null
$stderrTask = $null

try {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'java'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    [void]$startInfo.ArgumentList.Add('-jar')
    [void]$startInfo.ArgumentList.Add($resolvedJar)
    $startInfo.Environment['SPRING_PROFILES_ACTIVE'] = 'prod,loadtest-bar'
    $startInfo.Environment['SERVER_ADDRESS'] = '127.0.0.1'
    $startInfo.Environment['SERVER_PORT'] = [string]$Port
    $startInfo.Environment['SERVER_SSL_ENABLED'] = 'false'
    $startInfo.Environment['MEMBERSHIP_PAYMENT_LOADTEST_ENABLED'] = 'true'
    $startInfo.Environment['MEMBERSHIP_PAYMENT_LOADTEST_INFERENCE_STUB_ENABLED'] = 'true'
    $startInfo.Environment['MEMBERSHIP_PAYMENT_LOADTEST_VIDEO_URL'] = $SandboxVideoUrl
    $startInfo.Environment['AI_INFERENCE_CLI_PROXY_ENABLED'] = 'true'
    $startInfo.Environment['AI_INFERENCE_SPRING_CHAT_MODEL'] = 'openai'
    $startInfo.Environment['AI_INFERENCE_CLI_PROXY_BASE_URL'] = $stubBase
    $startInfo.Environment['CLI_PROXY_API_KEY'] = $proxyKey
    $startInfo.Environment['AI_CONVERSATION_INSTANCE_ID'] = $instanceId
    $startInfo.Environment['AI_CONVERSATION_WORKER_CONSUMERS'] = '1'
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw 'W16 loopback application process did not start.'
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    $readyDeadline = [datetimeoffset]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $ready = $false
    while ([datetimeoffset]::UtcNow -lt $readyDeadline -and -not $process.HasExited) {
        try {
            $probe = Invoke-WebRequest `
                -UseBasicParsing `
                -Method POST `
                -Uri "$stubBase/v1/chat/completions" `
                -Headers @{ Authorization = "Bearer $proxyKey" } `
                -ContentType 'application/json' `
                -Body '{"stream":false}' `
                -SkipHttpErrorCheck `
                -TimeoutSec 5
            if ([int]$probe.StatusCode -eq 200) {
                $ready = $true
                break
            }
        } catch {
            # 启动期间连接拒绝是预期状态；只在有界截止后裁决失败。
        }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) {
        throw 'W16 loopback application did not become ready before the deadline.'
    }

    & (Join-Path $PSScriptRoot 'Invoke-MembershipQuotaFirstUseControllerProbe.ps1') `
        -LoopbackBaseUrl $loopbackBase `
        -UsersCsv $usersPath `
        -PostgresUrl $PostgresUrl `
        -OutputFile $resolvedOutput `
        -BuildSha256 $jarSha256 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'W16 quota first-use Controller probe returned a non-zero exit code.'
    }
} finally {
    $proxyKey = $null
    [Array]::Clear($proxyKeyBytes, 0, $proxyKeyBytes.Length)
    if ($null -ne $process) {
        if (-not $process.HasExited) {
            $process.Kill($true)
            $process.WaitForExit(30000)
        }
        if ($null -ne $stdoutTask) {
            $stdoutTask.GetAwaiter().GetResult() |
                Set-Content -LiteralPath $stdoutPath -Encoding UTF8
        }
        if ($null -ne $stderrTask) {
            $stderrTask.GetAwaiter().GetResult() |
                Set-Content -LiteralPath $stderrPath -Encoding UTF8
        }
        $process.Dispose()
    }
}

Get-Content -Raw -LiteralPath $resolvedOutput | ConvertFrom-Json
