[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$postgresUrl = 'postgresql://postgres@127.0.0.1:5431/ai_temperate'
$redisContainer = 'redis7'
$rabbitContainer = 'rabbitmq1'
$applicationPort = 6655
$stage = 'INITIALIZATION'

. (Join-Path $PSScriptRoot 'MembershipBoundaryRedis.ps1')

$redisPatterns = @(
    'ait:*:payment:membership-order:v[12]:snapshot:*',
    'ait:*:payment:membership-order:v[12]:status:*',
    'ait:*:payment:provider-result:v[12]:status:*',
    'ait:*:payment:membership-order:v[12]:callback:*',
    'ait:*:payment:callback:v[12]:data:*',
    'ait:*:payment:callback:v[12]:idem:*',
    'ait:*:payment:callback:v[12]:order-idem:*',
    'ait:*:payment:callback:v[12]:provider-idem:*',
    'ait:*:payment:callback:v[12]:ready:all',
    'ait:*:payment:callback:v[12]:processing:all',
    'ait:*:payment:order-persist:v[12]:dirty:all',
    'ait:*:payment:order-persist:v[12]:processing:all')

function Resolve-RequiredCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    $commands = @(Get-Command $Name -CommandType Application `
        -ErrorAction SilentlyContinue)
    $command = $commands |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.Source) } |
        Select-Object -First 1
    if ($null -eq $command) {
        throw "Required executable is unavailable: $Name"
    }
    return [string]$command.Source
}

function Invoke-ResetPsql {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Sql
    )

    $output = @(& $script:psqlExecutable -X -w $script:postgresUrl `
        -v ON_ERROR_STOP=1 -q -A -t -F '|' -c $Sql 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "PostgreSQL command failed with exit code ${exitCode}: $($output -join ' ')"
    }
    return $output
}

function Assert-NoActiveMembershipRuntime {
    if ($null -eq (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) {
        throw 'Get-NetTCPConnection is unavailable.'
    }
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object {
            $_.LocalPort -eq $script:applicationPort -and
            $_.LocalAddress -in @('127.0.0.1', '::1', '0.0.0.0', '::')
        })
    if ($listeners.Count -ne 0) {
        throw "Application port $($script:applicationPort) still has a listener."
    }

    $commandMarkers = @(
        'Start-MembershipOrderCreateOptimizationRetest',
        'Start-MembershipSchedulerIndexHikariRetest',
        'Start-MembershipMillisecondBoundarySuite',
        'Invoke-MembershipMillisecondBoundaryWave',
        'membership-millisecond-boundary.jmx')
    $activeProcesses = @(Get-CimInstance Win32_Process -ErrorAction Stop |
        Where-Object {
            if ([int]$_.ProcessId -eq $PID -or
                    [string]::IsNullOrWhiteSpace([string]$_.CommandLine)) {
                return $false
            }
            foreach ($marker in $commandMarkers) {
                if (([string]$_.CommandLine).IndexOf(
                        $marker, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                    return $true
                }
            }
            return $false
        })
    if ($activeProcesses.Count -ne 0) {
        $details = $activeProcesses | ForEach-Object {
            "PID=$($_.ProcessId) Name=$($_.Name)"
        }
        throw "Membership load-test process is still active: $($details -join '; ')"
    }
}

function Assert-PostgresIdentity {
    $sql = @'
SELECT current_database(), COALESCE(host(inet_server_addr()), ''), inet_server_port();
'@
    $facts = @(Invoke-ResetPsql -Sql $sql | Where-Object {
        ([string]$_).Trim() -match '^[^|]+\|[^|]+\|\d+$'
    })
    if ($facts.Count -ne 1) {
        throw "PostgreSQL identity returned an unexpected result: $($facts -join '; ')"
    }
    $parts = @($facts[0].Trim().Split('|'))
    if ($parts.Count -ne 3 -or $parts[0] -ne 'ai_temperate' -or
            $parts[1] -notin @('127.0.0.1', '::1') -or $parts[2] -ne '5431') {
        throw "Refusing non-local PostgreSQL target: $($facts[0])"
    }
}

function Assert-RedisIdentity {
    $pong = @(Invoke-MembershipBoundaryRedisCli -Container $script:redisContainer `
        -Arguments @('PING'))
    if ($pong.Count -ne 1 -or ([string]$pong[0]).Trim() -ne 'PONG') {
        throw "Redis PING did not return PONG: $($pong -join '; ')"
    }
}

function Assert-MembershipRabbitQueuesEmpty {
    $arguments = @('exec', $script:rabbitContainer, 'rabbitmqctl',
        'list_queues', '--formatter', 'json', 'name',
        'messages_ready', 'messages_unacknowledged')
    $raw = @(& $script:dockerExecutable @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -or $raw.Count -eq 0) {
        throw "RabbitMQ queue inspection failed with exit code $exitCode."
    }
    try {
        $queues = @((($raw -join "`n") | ConvertFrom-Json))
    } catch {
        throw "RabbitMQ queue inspection returned invalid JSON: $($_.Exception.Message)"
    }
    $nonEmpty = @($queues | Where-Object {
        [string]$_.name -like 'membership.*' -and
        ([long]$_.messages_ready -ne 0L -or
            [long]$_.messages_unacknowledged -ne 0L)
    })
    if ($nonEmpty.Count -ne 0) {
        $details = $nonEmpty | ForEach-Object {
            "$($_.name):ready=$($_.messages_ready),unacked=$($_.messages_unacknowledged)"
        }
        throw "Membership RabbitMQ queues are not empty: $($details -join '; ')"
    }
}

function Reset-MembershipPostgresData {
    $sql = @'
BEGIN;
WITH deleted_callbacks AS (
    DELETE FROM membership_payment_callback
    RETURNING 1
),
deleted_orders AS (
    DELETE FROM membership_order
    RETURNING 1
),
reset_quotas AS (
    UPDATE user_membership_quota
    SET membership_tier = 0,
        quota_balance_minor = 0,
        quota_period_started_at = NULL,
        quota_period_ends_at = NULL,
        membership_expires_at = NULL
    RETURNING 1
)
SELECT
    (SELECT COUNT(*) FROM deleted_callbacks),
    (SELECT COUNT(*) FROM deleted_orders),
    (SELECT COUNT(*) FROM reset_quotas);

DO $membership_reset$
BEGIN
    IF EXISTS (SELECT 1 FROM membership_payment_callback LIMIT 1) THEN
        RAISE EXCEPTION 'membership_payment_callback is not empty after reset';
    END IF;
    IF EXISTS (SELECT 1 FROM membership_order LIMIT 1) THEN
        RAISE EXCEPTION 'membership_order is not empty after reset';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM user_membership_quota
        WHERE membership_tier <> 0
           OR quota_balance_minor <> 0
           OR quota_period_started_at IS NOT NULL
           OR quota_period_ends_at IS NOT NULL
           OR membership_expires_at IS NOT NULL
        LIMIT 1
    ) THEN
        RAISE EXCEPTION 'user_membership_quota contains a non-reset row';
    END IF;
END
$membership_reset$;
COMMIT;
'@
    $output = @(Invoke-ResetPsql -Sql $sql)
    $countLines = @($output | Where-Object {
        ([string]$_).Trim() -match '^\d+\|\d+\|\d+$'
    })
    if ($countLines.Count -ne 1) {
        throw "PostgreSQL reset did not return exactly one count record: $($output -join '; ')"
    }
    $parts = @($countLines[0].Trim().Split('|'))
    return [pscustomobject][ordered]@{
        deletedCallbackCount = [long]$parts[0]
        deletedOrderCount = [long]$parts[1]
        resetQuotaCount = [long]$parts[2]
    }
}

function Get-MembershipRedisKeys {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Pattern
    )

    return @(Invoke-MembershipBoundaryRedisCli -Container $script:redisContainer `
        -Arguments @('--scan', '--pattern', $Pattern, '--count', '500') |
        ForEach-Object { ([string]$_).Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Reset-MembershipRedisData {
    $matchedKeys = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    foreach ($pattern in $script:redisPatterns) {
        foreach ($key in @(Get-MembershipRedisKeys -Pattern $pattern)) {
            [void]$matchedKeys.Add($key)
        }
    }

    $keys = @($matchedKeys)
    $unlinkedKeyCount = 0L
    for ($offset = 0; $offset -lt $keys.Count; $offset += 100) {
        $lastIndex = $offset + [Math]::Min(100, $keys.Count - $offset) - 1
        $batch = @($keys[$offset..$lastIndex])
        $result = @(Invoke-MembershipBoundaryRedisCli -Container $script:redisContainer `
            -Arguments (@('UNLINK') + $batch))
        if ($result.Count -ne 1) {
            throw "Redis UNLINK returned an unexpected result: $($result -join '; ')"
        }
        $removed = 0L
        if (-not [long]::TryParse(
                ([string]$result[0]).Trim(),
                [Globalization.NumberStyles]::Integer,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$removed) -or $removed -lt 0L) {
            throw "Redis UNLINK returned a non-numeric count: $($result[0])"
        }
        $unlinkedKeyCount += $removed
    }

    foreach ($pattern in $script:redisPatterns) {
        $remaining = @(Get-MembershipRedisKeys -Pattern $pattern)
        if ($remaining.Count -ne 0) {
            throw "Redis artifact remains after reset: pattern=$pattern key=$($remaining[0])"
        }
    }

    return [pscustomobject][ordered]@{
        matchedKeyCount = [long]$keys.Count
        unlinkedKeyCount = $unlinkedKeyCount
        verifiedPatternCount = [long]$script:redisPatterns.Count
    }
}

try {
    $stage = 'PREFLIGHT_TOOLS'
    $script:psqlExecutable = Resolve-RequiredCommand -Name 'psql'
    $script:dockerExecutable = Resolve-RequiredCommand -Name 'docker'

    $stage = 'PREFLIGHT_RUNTIME'
    Assert-NoActiveMembershipRuntime

    $stage = 'PREFLIGHT_POSTGRES'
    Assert-PostgresIdentity

    $stage = 'PREFLIGHT_REDIS'
    Assert-RedisIdentity

    $stage = 'PREFLIGHT_RABBITMQ'
    Assert-MembershipRabbitQueuesEmpty

    $stage = 'POSTGRES_RESET'
    $databaseResult = Reset-MembershipPostgresData
    Write-Host (
        "POSTGRES_RESET deletedCallbacks=$($databaseResult.deletedCallbackCount) " +
        "deletedOrders=$($databaseResult.deletedOrderCount) " +
        "resetQuotas=$($databaseResult.resetQuotaCount)") -ForegroundColor Green

    $stage = 'REDIS_RESET'
    $redisResult = Reset-MembershipRedisData
    Write-Host (
        "REDIS_RESET matchedKeys=$($redisResult.matchedKeyCount) " +
        "unlinkedKeys=$($redisResult.unlinkedKeyCount) " +
        "verifiedPatterns=$($redisResult.verifiedPatternCount)") -ForegroundColor Green

    $stage = 'COMPLETE'
    Write-Host 'RESET_COMPLETE' -ForegroundColor Green
    exit 0
} catch {
    Write-Host (
        "RESET_FAILED stage=$stage message=$($_.Exception.Message)") -ForegroundColor Red
    exit 1
}
