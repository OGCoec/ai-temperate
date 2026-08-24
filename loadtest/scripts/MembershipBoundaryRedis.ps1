[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-MembershipBoundaryRedisPassword {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Container
    )

    foreach ($name in @('MEMBERSHIP_BOUNDARY_REDIS_PASSWORD', 'REDIS_PASSWORD')) {
        $candidate = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrEmpty($candidate)) {
            return $candidate
        }
    }

    $inspectJson = (& docker inspect $Container 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($inspectJson)) {
        throw "Redis container inspection failed: $Container"
    }
    $inspection = @($inspectJson | ConvertFrom-Json)
    if ($inspection.Count -ne 1) {
        throw "Redis container inspection returned an unexpected result: $Container"
    }

    $command = @($inspection[0].Config.Cmd)
    for ($index = 0; $index -lt $command.Count; $index += 1) {
        $argument = [string]$command[$index]
        if ($argument -eq '--requirepass' -and $index + 1 -lt $command.Count) {
            return [string]$command[$index + 1]
        }
        if ($argument.StartsWith('--requirepass=', [StringComparison]::Ordinal)) {
            return $argument.Substring('--requirepass='.Length)
        }
    }

    foreach ($entry in @($inspection[0].Config.Env)) {
        foreach ($prefix in @('REDISCLI_AUTH=', 'REDIS_PASSWORD=')) {
            if ([string]$entry.StartsWith($prefix, [StringComparison]::Ordinal)) {
                return ([string]$entry).Substring($prefix.Length)
            }
        }
    }

    return $null
}

function Invoke-MembershipBoundaryRedisCli {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Container,

        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $password = Get-MembershipBoundaryRedisPassword -Container $Container
    $hadPreviousAuth = Test-Path Env:REDISCLI_AUTH
    $previousAuth = $env:REDISCLI_AUTH
    try {
        $dockerArguments = @('exec')
        if (-not [string]::IsNullOrEmpty($password)) {
            # 凭据只通过子进程环境传递，避免出现在命令参数和测试证据中。
            $env:REDISCLI_AUTH = $password
            $dockerArguments += @('--env', 'REDISCLI_AUTH')
        }
        $dockerArguments += @($Container, 'redis-cli')
        $dockerArguments += $Arguments

        $output = @(& docker @dockerArguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        if ($hadPreviousAuth) {
            $env:REDISCLI_AUTH = $previousAuth
        } else {
            Remove-Item Env:REDISCLI_AUTH -ErrorAction SilentlyContinue
        }
    }

    if ($exitCode -ne 0) {
        throw "Authenticated Redis inspection failed: container=$Container exitCode=$exitCode"
    }
    return $output
}

function Remove-MembershipBoundaryRedisOrderArtifacts {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Container,

        [Parameter(Mandatory = $true)]
        [string[]] $OrderIds
    )

    if ($OrderIds.Count -gt 40000) {
        throw "Redis cleanup manifest exceeds 40,000 order IDs: $($OrderIds.Count)"
    }
    $ownedIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($orderId in $OrderIds) {
        if ($orderId -notmatch '^[A-Za-z0-9_-]{22}$' -or -not $ownedIds.Add($orderId)) {
            throw "Redis cleanup manifest contains an invalid or duplicate order ID: $orderId"
        }
    }

    # 只扫描会员支付命名空间，再按公共订单 ID 精确过滤；不会删除其他运行或业务域的 Key。
    $matchedKeys = [Collections.Generic.List[string]]::new()
    foreach ($pattern in @('ait:*:payment:*:v1:*', 'ait:*:payment:*:v2:*')) {
        foreach ($key in @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('--scan', '--pattern', $pattern))) {
            $separator = $key.LastIndexOf(':')
            if ($separator -ge 0 -and $ownedIds.Contains($key.Substring($separator + 1))) {
                $matchedKeys.Add($key)
            }
        }
    }

    for ($offset = 0; $offset -lt $matchedKeys.Count; $offset += 100) {
        $batch = @($matchedKeys.GetRange(
                $offset,
                [Math]::Min(100, $matchedKeys.Count - $offset)))
        [void](Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments (@('UNLINK') + $batch))
    }

    foreach ($pattern in @('ait:*:payment:*:v1:*', 'ait:*:payment:*:v2:*')) {
        foreach ($key in @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('--scan', '--pattern', $pattern))) {
            $separator = $key.LastIndexOf(':')
            if ($separator -ge 0 -and $ownedIds.Contains($key.Substring($separator + 1))) {
                throw "Run-owned Redis artifact remains after cleanup: $key"
            }
        }
    }
    return $matchedKeys.Count
}
