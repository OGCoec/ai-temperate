[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('detail', 'pay-and-notify', 'notify-retry', 'refund')]
    [string]$Operation,
    [string]$TradeNo = '',
    [string]$OutTradeNo = '',
    [string]$BaseUrl = 'https://ihaveagoddamnplan.com',
    [string]$CredentialFile = 'C:\Users\damn\AppData\Local\Temp\新建文件夹\新建 Text Document.txt'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-BarCredential([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'BAR credential file is unavailable.'
    }
    $raw = [IO.File]::ReadAllText($Path)
    if ([string]::IsNullOrWhiteSpace($raw)) {
        throw 'BAR credential file is empty.'
    }

    $email = ''
    $password = ''
    try {
        $parsed = $raw | ConvertFrom-Json -ErrorAction Stop
        $email = [string]$parsed.email
        $password = [string]$parsed.password
    } catch {
        $values = @{}
        $lines = @($raw -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        foreach ($line in $lines) {
            if ($line -match '^\s*([^:=]+)\s*[:=]\s*(.*)\s*$') {
                $values[$matches[1].Trim().ToLowerInvariant()] = $matches[2]
            }
        }
        $email = [string]$values['email']
        $password = [string]$values['password']
        if ([string]::IsNullOrWhiteSpace($email) -and $lines.Count -ge 2) {
            $email = $lines[0].Trim()
            $password = $lines[1]
        }
    }
    if ([string]::IsNullOrWhiteSpace($email) -or [string]::IsNullOrWhiteSpace($password)) {
        throw 'BAR credential file must contain email and password.'
    }
    return [pscustomobject]@{ email = $email.Trim(); password = $password }
}

function Invoke-BarRequest(
    [string]$Method,
    [string]$Path,
    [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
    [string]$CsrfToken = '',
    [object]$Body = $null) {
    $headers = @{ Accept = 'application/json' }
    if (-not [string]::IsNullOrWhiteSpace($CsrfToken)) {
        $headers['X-CSRF-Token'] = $CsrfToken
    }
    $parameters = @{
        Method = $Method
        Uri = $BaseUrl.TrimEnd('/') + $Path
        WebSession = $Session
        Headers = $headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $parameters['ContentType'] = 'application/json'
        $parameters['Body'] = $Body | ConvertTo-Json -Compress -Depth 5
    }
    return Invoke-RestMethod @parameters
}

function Resolve-BarTradeNo(
    [string]$RequestedTradeNo,
    [string]$MerchantOrderNo,
    [Microsoft.PowerShell.Commands.WebRequestSession]$Session) {
    if (-not [string]::IsNullOrWhiteSpace($RequestedTradeNo)) {
        if ($RequestedTradeNo -notmatch '^[0-9]{1,19}$') {
            throw 'BAR tradeNo format is invalid.'
        }
        return $RequestedTradeNo
    }
    if ([string]::IsNullOrWhiteSpace($MerchantOrderNo)) {
        throw 'Either TradeNo or OutTradeNo is required.'
    }
    $encoded = [Uri]::EscapeDataString($MerchantOrderNo)
    $response = Invoke-BarRequest 'GET' "/api/admin/orders?search=$encoded&page=0&size=20" $Session
    if ($response.code -ne 0) {
        throw 'BAR order lookup returned a business error.'
    }
    $matches = @($response.data.items | Where-Object {
        [string]$_.out_trade_no -eq $MerchantOrderNo
    })
    if ($matches.Count -ne 1) {
        throw "BAR order lookup expected one exact merchant order but found $($matches.Count)."
    }
    return [string]$matches[0].trade_no
}

$credential = Read-BarCredential $CredentialFile
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$loginBody = @{ email = $credential.email; password = $credential.password }
$login = Invoke-BarRequest 'POST' '/api/admin/auth/login' $session '' $loginBody
if ($login.code -ne 0) {
    throw 'BAR administrator login returned a business error.'
}

# 凭据只在登录请求期间存在于进程内存；登录后立即清除局部引用，后续命令只使用 HttpOnly 会话 Cookie。
$credential = $null
$loginBody = $null
$csrfCookie = $session.Cookies.GetCookies([Uri]$BaseUrl)['bar_admin_csrf']
if ($null -eq $csrfCookie -or [string]::IsNullOrWhiteSpace($csrfCookie.Value)) {
    throw 'BAR CSRF session cookie is unavailable.'
}
$csrf = $csrfCookie.Value
$resolvedTradeNo = Resolve-BarTradeNo $TradeNo $OutTradeNo $session

$path = switch ($Operation) {
    'detail' { "/api/admin/orders/$resolvedTradeNo" }
    'pay-and-notify' { "/api/admin/orders/$resolvedTradeNo/pay-and-notify" }
    'notify-retry' { "/api/admin/orders/$resolvedTradeNo/notify/retry" }
    'refund' { "/api/admin/orders/$resolvedTradeNo/refund" }
}
$method = if ($Operation -eq 'detail') { 'GET' } else { 'POST' }
$response = Invoke-BarRequest $method $path $session $(if ($method -eq 'POST') { $csrf } else { '' })
if ($response.code -ne 0) {
    throw "BAR $Operation returned a business error."
}

$order = if ($Operation -eq 'notify-retry') { $null } elseif ($Operation -eq 'detail') {
    $response.data.order
} else {
    $response.data
}
[pscustomobject]@{
    operation = $Operation
    tradeNo = $resolvedTradeNo
    outTradeNo = if ($null -eq $order) { $OutTradeNo } else { [string]$order.out_trade_no }
    tradeStatus = if ($null -eq $order) { $null } else { [string]$order.trade_status }
    notifyStatus = if ($null -eq $order) { [string]$response.data.status } else { [string]$order.notify_status }
    observedAt = [DateTimeOffset]::UtcNow.ToString('O')
} | ConvertTo-Json -Compress
