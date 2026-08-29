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
        [string[]] $OrderIds,

        [string[]] $CallbackIds = @()
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

    if ($CallbackIds.Count -gt 40000) {
        throw "Redis cleanup manifest exceeds 40,000 callback IDs: $($CallbackIds.Count)"
    }
    $ownedCallbackIds = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    foreach ($callbackId in $CallbackIds) {
        if ($callbackId -notmatch '^[A-Za-z0-9_-]{22}$' -or
                -not $ownedCallbackIds.Add($callbackId)) {
            throw "Redis cleanup manifest contains an invalid or duplicate callback ID: $callbackId"
        }
    }

    # 只扫描四类订单/回调状态事实，再按清单中的公共 ID 精确过滤；短期幂等 Key 保留到 TTL 自然回收。
    $keyScopes = @(
        [pscustomobject]@{
            pattern = 'ait:*:payment:membership-order:v[12]:snapshot:*'
            ids = $ownedIds
        },
        [pscustomobject]@{
            pattern = 'ait:*:payment:membership-order:v[12]:callback:*'
            ids = $ownedIds
        },
        [pscustomobject]@{
            pattern = 'ait:*:payment:provider-result:v[12]:status:*'
            ids = $ownedIds
        },
        [pscustomobject]@{
            pattern = 'ait:*:payment:callback:v[12]:data:*'
            ids = $ownedCallbackIds
        }
    )
    $matchedKeys = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    foreach ($scope in $keyScopes) {
        foreach ($key in @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('--scan', '--pattern', $scope.pattern))) {
            $separator = $key.LastIndexOf(':')
            if ($separator -ge 0 -and
                    $scope.ids.Contains($key.Substring($separator + 1))) {
                [void]$matchedKeys.Add($key)
            }
        }
    }

    $matchedKeyArray = @($matchedKeys)
    for ($offset = 0; $offset -lt $matchedKeyArray.Count; $offset += 100) {
        $batch = @($matchedKeyArray[$offset..(
            $offset + [Math]::Min(100, $matchedKeyArray.Count - $offset) - 1)])
        [void](Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments (@('UNLINK') + $batch))
    }

    $removedCallbackMembers = 0L
    foreach ($pattern in @(
            'ait:*:payment:callback:v[12]:ready:all',
            'ait:*:payment:callback:v[12]:processing:all')) {
        foreach ($key in @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('--scan', '--pattern', $pattern))) {
            $members = @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('ZRANGE', $key, '0', '-1') |
                Where-Object { $ownedCallbackIds.Contains([string]$_) })
            for ($offset = 0; $offset -lt $members.Count; $offset += 100) {
                $batch = @($members[$offset..(
                    $offset + [Math]::Min(100, $members.Count - $offset) - 1)])
                $result = @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                    -Arguments (@('ZREM', $key) + $batch))
                if ($result.Count -ne 1) {
                    throw "Redis callback work-set cleanup returned an invalid result: $key"
                }
                $removedCallbackMembers += [long]$result[0]
            }
        }
    }

    $removedOrderMembers = 0L
    foreach ($pattern in @(
            'ait:*:payment:order-persist:v[12]:dirty:all',
            'ait:*:payment:order-persist:v[12]:processing:all')) {
        foreach ($key in @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('--scan', '--pattern', $pattern))) {
            $members = @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('ZRANGE', $key, '0', '-1') |
                Where-Object {
                    $separator = ([string]$_).IndexOf('#')
                    $separator -gt 0 -and $ownedIds.Contains(
                        ([string]$_).Substring(0, $separator))
                })
            for ($offset = 0; $offset -lt $members.Count; $offset += 100) {
                $batch = @($members[$offset..(
                    $offset + [Math]::Min(100, $members.Count - $offset) - 1)])
                $result = @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                    -Arguments (@('ZREM', $key) + $batch))
                if ($result.Count -ne 1) {
                    throw "Redis order work-set cleanup returned an invalid result: $key"
                }
                $removedOrderMembers += [long]$result[0]
            }
        }
    }

    foreach ($scope in $keyScopes) {
        foreach ($key in @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('--scan', '--pattern', $scope.pattern))) {
            $separator = $key.LastIndexOf(':')
            if ($separator -ge 0 -and
                    $scope.ids.Contains($key.Substring($separator + 1))) {
                throw "Run-owned Redis artifact remains after cleanup: $key"
            }
        }
    }
    foreach ($pattern in @(
            'ait:*:payment:callback:v[12]:ready:all',
            'ait:*:payment:callback:v[12]:processing:all')) {
        foreach ($key in @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('--scan', '--pattern', $pattern))) {
            $remaining = @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('ZRANGE', $key, '0', '-1') |
                Where-Object { $ownedCallbackIds.Contains([string]$_) })
            if ($remaining.Count -ne 0) {
                throw "Run-owned callback member remains in Redis work set: $key"
            }
        }
    }
    foreach ($pattern in @(
            'ait:*:payment:order-persist:v[12]:dirty:all',
            'ait:*:payment:order-persist:v[12]:processing:all')) {
        foreach ($key in @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('--scan', '--pattern', $pattern))) {
            $remaining = @(Invoke-MembershipBoundaryRedisCli -Container $Container `
                -Arguments @('ZRANGE', $key, '0', '-1') |
                Where-Object {
                    $separator = ([string]$_).IndexOf('#')
                    $separator -gt 0 -and $ownedIds.Contains(
                        ([string]$_).Substring(0, $separator))
                })
            if ($remaining.Count -ne 0) {
                throw "Run-owned order member remains in Redis work set: $key"
            }
        }
    }

    return [pscustomobject]@{
        unlinkedKeyCount = $matchedKeys.Count
        removedCallbackWorkMemberCount = $removedCallbackMembers
        removedOrderWorkMemberCount = $removedOrderMembers
    }
}

function Remove-MembershipBoundaryRedisExactWarmupArtifacts {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Container,

        [Parameter(Mandatory = $true)]
        [string[]] $OrderIds,

        [Parameter(Mandatory = $true)]
        [string[]] $CallbackIds,

        [string] $Environment = 'local'
    )

    if ($Environment -notmatch '^[a-z0-9-]{1,32}$') {
        throw 'Redis warmup cleanup environment must be a bounded lowercase namespace.'
    }
    if ($OrderIds.Count -notin @(5000, 10000) -or $CallbackIds.Count -ne $OrderIds.Count) {
        throw 'Redis warmup cleanup requires one exact 5K or 10K order/callback manifest.'
    }
    $ownedOrderIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($orderId in $OrderIds) {
        if ($orderId -notmatch '^[A-Za-z0-9_-]{22}$' -or -not $ownedOrderIds.Add($orderId)) {
            throw "Redis warmup cleanup contains an invalid or duplicate order ID: $orderId"
        }
    }
    $ownedCallbackIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($callbackId in $CallbackIds) {
        if ($callbackId -notmatch '^[A-Za-z0-9_-]{22}$' -or
                -not $ownedCallbackIds.Add($callbackId)) {
            throw "Redis warmup cleanup contains an invalid or duplicate callback ID: $callbackId"
        }
    }

    # 预热结束门禁已经要求四个工作集合归零；这里使用固定命名空间逐个精确核验，禁止 SCAN 猜测环境。
    $workKeys = foreach ($version in @(1, 2)) {
        'ait:{0}:payment:callback:v{1}:ready:all' -f $Environment, $version
        'ait:{0}:payment:callback:v{1}:processing:all' -f $Environment, $version
        'ait:{0}:payment:order-persist:v{1}:dirty:all' -f $Environment, $version
        'ait:{0}:payment:order-persist:v{1}:processing:all' -f $Environment, $version
    }
    foreach ($workKey in $workKeys) {
        $cardinality = @(Invoke-MembershipBoundaryRedisCli -Container $Container `
            -Arguments @('ZCARD', $workKey))
        if ($cardinality.Count -ne 1 -or [long]$cardinality[0] -ne 0L) {
            throw "Redis warmup work set is not empty before exact cleanup: $workKey"
        }
    }

    # 每个公共 ID 只展开为 RedisKeyFactory 已定义的有限固定 Key；UNLINK 分批发送，不做命名空间扫描。
    $keys = [Collections.Generic.List[string]]::new()
    foreach ($version in @(1, 2)) {
        foreach ($orderId in $OrderIds) {
            $keys.Add(('ait:{0}:payment:membership-order:v{1}:snapshot:{2}' -f
                    $Environment, $version, $orderId))
            $keys.Add(('ait:{0}:payment:membership-order:v{1}:callback:{2}' -f
                    $Environment, $version, $orderId))
            $keys.Add(('ait:{0}:payment:provider-result:v{1}:status:{2}' -f
                    $Environment, $version, $orderId))
        }
        foreach ($callbackId in $CallbackIds) {
            $keys.Add(('ait:{0}:payment:callback:v{1}:data:{2}' -f
                    $Environment, $version, $callbackId))
        }
    }
    $unlinkedKeyCount = 0L
    for ($offset = 0; $offset -lt $keys.Count; $offset += 100) {
        $batch = @($keys[$offset..(
            $offset + [Math]::Min(100, $keys.Count - $offset) - 1)])
        $result = @(Invoke-MembershipBoundaryRedisCli -Container $Container `
            -Arguments (@('UNLINK') + $batch))
        if ($result.Count -ne 1) {
            throw 'Redis exact warmup UNLINK returned an invalid result.'
        }
        $unlinkedKeyCount += [long]$result[0]
    }
    for ($offset = 0; $offset -lt $keys.Count; $offset += 100) {
        $batch = @($keys[$offset..(
            $offset + [Math]::Min(100, $keys.Count - $offset) - 1)])
        $remaining = @(Invoke-MembershipBoundaryRedisCli -Container $Container `
            -Arguments (@('EXISTS') + $batch))
        if ($remaining.Count -ne 1 -or [long]$remaining[0] -ne 0L) {
            throw 'A run-owned Redis warmup key remains after exact cleanup.'
        }
    }

    return [pscustomobject]@{
        expectedKeyCount = $keys.Count
        unlinkedKeyCount = $unlinkedKeyCount
        verifiedWorkSetCount = $workKeys.Count
    }
}
