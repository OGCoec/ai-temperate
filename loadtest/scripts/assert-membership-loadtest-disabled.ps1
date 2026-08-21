[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $BaseUrl,
    [string] $AccessToken = '',
    [string] $UsersCsv = 'loadtest/local/loadtest-users.csv'
)

Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($AccessToken)) {
    $usersPath = if ([System.IO.Path]::IsPathRooted($UsersCsv)) {
        $UsersCsv
    } else {
        Join-Path $repoRoot $UsersCsv
    }
    if (-not (Test-Path -LiteralPath $usersPath)) {
        throw 'Loadtest token CSV is missing; run the realtime suite before the disabled-switch check.'
    }
    $firstUser = @(Import-Csv -LiteralPath $usersPath | Select-Object -First 1)
    if ($firstUser.Count -ne 1 `
        -or [string]::IsNullOrWhiteSpace([string]$firstUser[0].accessToken)) {
        throw 'Loadtest token CSV does not contain a reusable short-term Access Token.'
    }
    # Token 只保留在进程内请求头中，不打印、不复制到关闭开关验收产物。
    $AccessToken = [string]$firstUser[0].accessToken
}
$headers = @{ Authorization = "Bearer $AccessToken"; 'Content-Type' = 'application/json' }
$body = '{"targetTier":"GO","payType":"alipay","idempotencyKey":"00000000-0000-4000-8000-000000000001"}'
# PowerShell 7 直接返回非 2xx 响应，避免不同 HttpClient 异常类型缺少 Response 属性而产生假阳性退出码。
$response = Invoke-WebRequest -UseBasicParsing -SkipHttpErrorCheck `
    -Method Post `
    -Uri ($BaseUrl.TrimEnd('/') + '/api/user/membership-orders') `
    -Headers $headers `
    -Body $body `
    -TimeoutSec 10
if ([int]$response.StatusCode -in @(401,403)) {
    Write-Host "AT-only disabled check passed with HTTP $([int]$response.StatusCode)."
    exit 0
}
if ([int]$response.StatusCode -in @(200,201)) {
    throw 'AT-only request succeeded while loadtest switch was expected to be disabled.'
}
throw "AT-only disabled check returned unexpected HTTP $([int]$response.StatusCode)."
