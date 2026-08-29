Set-StrictMode -Version Latest

function Resolve-MembershipBoundaryResetMode {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 80000)]
        [long] $ManifestOrderCount,
        [Parameter(Mandatory = $true)]
        [string] $DatabaseFacts
    )

    $parts = @($DatabaseFacts.Trim().Split('|'))
    if ($parts.Count -ne 6) {
        throw 'Previous boundary database facts must contain exactly six counters.'
    }
    $values = foreach ($part in $parts) {
        $value = 0L
        if (-not [long]::TryParse(
                $part,
                [Globalization.NumberStyles]::Integer,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$value) -or $value -lt 0L) {
            throw "Previous boundary database fact is not a non-negative integer: $part"
        }
        $value
    }
    $orderCount = $values[0]
    $userCount = $values[1]
    $pendingUnresolvedCount = $values[2]
    $terminalResolvedCount = $values[3]
    $callbackCount = $values[4]
    $unresolvedCallbackCount = $values[5]
    if ($orderCount -ne $ManifestOrderCount -or
            $userCount -ne $ManifestOrderCount) {
        throw "Previous boundary database facts do not match manifest cardinality: manifest=$ManifestOrderCount facts=$DatabaseFacts"
    }
    if ($callbackCount -gt $ManifestOrderCount) {
        throw "Previous boundary callback count exceeds the exact manifest: $DatabaseFacts"
    }
    if ($unresolvedCallbackCount -ne 0L) {
        throw "Previous boundary database contains unresolved callbacks: $DatabaseFacts"
    }

    # 完整终态运行可以没有回调（例如首段性能硬门禁在回调目标时间前停止），
    # 但所有订单仍须已终态且权益已裁决，已存在的回调也必须全部完成裁决。
    if ($pendingUnresolvedCount -eq 0L -and
            $terminalResolvedCount -eq $ManifestOrderCount) {
        return [pscustomobject][ordered]@{
            mode = 'TERMINAL_RESOLVED'
            endpointPath = 'reset'
            callbackCount = $callbackCount
            expectedDatabaseFacts = "$ManifestOrderCount|$ManifestOrderCount|0|$ManifestOrderCount|$callbackCount|0"
        }
    }

    # 失败运行入口只接受完整 PENDING/未裁决集合且绝不能已有回调；CLOSING、混合状态或部分裁决
    # 不会命中任一分支，从而在任何 Redis/数据库删除发生前失败。
    if ($pendingUnresolvedCount -eq $ManifestOrderCount -and
            $terminalResolvedCount -eq 0L -and $callbackCount -eq 0L) {
        return [pscustomobject][ordered]@{
            mode = 'FAILED_PENDING'
            endpointPath = 'failed-run-reset'
            callbackCount = 0L
            expectedDatabaseFacts = "$ManifestOrderCount|$ManifestOrderCount|$ManifestOrderCount|0|0|0"
        }
    }

    throw "Previous boundary orders are not in a single safe reset state: $DatabaseFacts"
}
