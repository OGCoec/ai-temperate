[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$resetScriptPath = Join-Path $repositoryRoot 'loadtest\scripts\Reset-AllMembershipData.ps1'
$launcherPath = Join-Path $repositoryRoot 'loadtest\scripts\Reset-AllMembershipData.bat'

if (-not (Test-Path -LiteralPath $resetScriptPath -PathType Leaf)) {
    throw 'Reset-AllMembershipData.ps1 does not exist.'
}
if (-not (Test-Path -LiteralPath $launcherPath -PathType Leaf)) {
    throw 'Reset-AllMembershipData.bat does not exist.'
}

$resetSource = Get-Content -Raw -LiteralPath $resetScriptPath
$launcherSource = Get-Content -Raw -LiteralPath $launcherPath

$requiredSql = @(
    'DELETE FROM membership_payment_callback',
    'DELETE FROM membership_order',
    'UPDATE user_membership_quota',
    'membership_tier = 0',
    'quota_balance_minor = 0',
    'quota_period_started_at = NULL',
    'quota_period_ends_at = NULL',
    'membership_expires_at = NULL')
foreach ($fragment in $requiredSql) {
    if (-not $resetSource.Contains($fragment)) {
        throw "Full Membership reset is missing SQL contract: $fragment"
    }
}

$requiredRedisPatterns = @(
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
foreach ($pattern in $requiredRedisPatterns) {
    if (-not $resetSource.Contains("'$pattern'")) {
        throw "Full Membership reset is missing Redis pattern: $pattern"
    }
}

foreach ($fragment in @(
        "'postgresql://postgres@127.0.0.1:5431/ai_temperate'",
        "'redis7'",
        "'rabbitmq1'",
        '$commands = @(Get-Command',
        'Select-Object -First 1',
        'return [string]$command.Source',
        'Get-NetTCPConnection',
        "LocalAddress -in @('127.0.0.1', '::1', '0.0.0.0', '::')",
        'Get-CimInstance Win32_Process',
        'current_database()',
        "COALESCE(host(inet_server_addr()), '')",
        'messages_ready',
        'messages_unacknowledged',
        "'PONG'",
        "'--count', '500'",
        "@('UNLINK') + `$batch",
        '[Math]::Min(100',
        'Redis artifact remains after reset',
        'RESET_COMPLETE')) {
    if (-not $resetSource.Contains($fragment)) {
        throw "Full Membership reset is missing safety contract: $fragment"
    }
}

if ($resetSource.Contains(
        "Where-Object { `$_.LocalPort -eq `$script:applicationPort }")) {
    throw 'A non-loopback listener must not be treated as the local load-test application.'
}

foreach ($forbidden in @(
        'Read-Host',
        'TRUNCATE',
        "@('KEYS'",
        "'ait:*:payment:order-persist:v[12]:lock:*'")) {
    if ($resetSource.Contains($forbidden)) {
        throw "Full Membership reset contains forbidden behavior: $forbidden"
    }
}

foreach ($fragment in @(
        '%~dp0',
        'pwsh',
        '-NoProfile',
        '-ExecutionPolicy Bypass',
        'Reset-AllMembershipData.ps1',
        '%ERRORLEVEL%',
        'pause',
        'exit /b')) {
    if (-not $launcherSource.Contains($fragment)) {
        throw "Membership reset BAT is missing launcher contract: $fragment"
    }
}
if ($launcherSource -match '(?im)^\s*(set\s+/p|choice)\b') {
    throw 'Membership reset BAT must not ask for confirmation.'
}

Write-Output 'PASS: full Membership reset BAT and PowerShell contracts are complete.'
