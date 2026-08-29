[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $CaseName,
    [Parameter(Mandatory = $true)]
    [ValidateRange(0, 15)]
    [int] $UserIndex,
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        'PAY',
        'UNPAID',
        'CANCEL',
        'CANCEL_THEN_PAY',
        'PAY_REPLAY_NOTIFY',
        'PAY_EXPECT_REJECTED')]
    [string] $Action,
    [ValidateSet('NEXT', 'GO', 'PLUS', 'PRO', 'MAX')]
    [string] $TargetTier = 'NEXT',
    [ValidateSet('alipay', 'wxpay')]
    [string] $PayType = 'alipay',
    [ValidateSet('CREATED', 'EXPIRES', 'HARD_CLOSE')]
    [string] $ActionAnchor = 'CREATED',
    [int] $ActionOffsetSeconds = 5,
    [ValidateRange(0, 300)]
    [int] $PayAfterCancelSeconds = 5,
    [ValidateRange(0, 10)]
    [int] $NotifyReplayCount = 0,
    [Parameter(Mandatory = $true)]
    [ValidateSet('PAID', 'CANCELLED', 'CLOSED')]
    [string] $ExpectedStatus,
    [string] $MainBaseUrl = 'https://niko000o.site',
    [string] $BarBaseUrl = 'https://ihaveagoddamnplan.com',
    [string] $UsersCsv = 'loadtest/local/loadtest-users.csv',
    [string] $CredentialFile = 'C:\Users\damn\AppData\Local\Temp\新建文件夹\新建 Text Document.txt',
    [Parameter(Mandatory = $true)]
    [string] $OutputFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$usersPath = if ([IO.Path]::IsPathRooted($UsersCsv)) { $UsersCsv } else { Join-Path $repoRoot $UsersCsv }
$rows = @(Import-Csv -LiteralPath $usersPath)
if ($rows.Count -ne 16) { throw 'BAR case requires exactly sixteen approved token rows.' }
$row = $rows[$UserIndex]
$accessToken = [string]$row.accessToken
if ([string]::IsNullOrWhiteSpace($accessToken)) { throw 'Selected BAR test token is empty.' }
$headers = @{ Authorization = "Bearer $accessToken"; Accept = 'application/json' }

function Invoke-MainRequest(
    [string] $Method,
    [string] $Path,
    [object] $Body = $null) {
    $parameters = @{
        Method = $Method
        Uri = $MainBaseUrl.TrimEnd('/') + $Path
        Headers = $headers
        TimeoutSec = 45
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 6
    }
    $response = Invoke-WebRequest @parameters
    return [pscustomobject]@{
        status = [int]$response.StatusCode
        body = [string]$response.Content
        headers = $response.Headers
    }
}

function Require-Status([object] $Response, [int[]] $Expected, [string] $Operation) {
    if ($Expected -notcontains [int]$Response.status) {
        throw "$Operation expected $($Expected -join '/') but received $($Response.status)."
    }
}

function Wait-Until([datetimeoffset] $Target) {
    while ([datetimeoffset]::UtcNow -lt $Target) {
        $remaining = $Target - [datetimeoffset]::UtcNow
        Start-Sleep -Seconds ([Math]::Max(1, [Math]::Min(30, [int][Math]::Ceiling($remaining.TotalSeconds))))
    }
}

function Read-Order([string] $OrderId) {
    $response = Invoke-MainRequest 'GET' "/api/user/membership-orders/$OrderId"
    Require-Status $response @(200) 'order lookup'
    return $response.body | ConvertFrom-Json
}

function Wait-ForOrderStatus(
    [string] $OrderId,
    [string] $Status,
    [datetimeoffset] $Deadline) {
    do {
        $order = Read-Order $OrderId
        if ([string]$order.status -eq $Status) { return $order }
        Start-Sleep -Seconds 2
    } while ([datetimeoffset]::UtcNow -lt $Deadline)
    throw "Order did not reach $Status before the case deadline."
}

function Invoke-BarAdmin([string] $Operation, [string] $OrderId) {
    $adminScript = Join-Path $PSScriptRoot 'Invoke-BarSandboxAdminOperation.ps1'
    $json = & $adminScript `
        -Operation $Operation `
        -OutTradeNo $OrderId `
        -BaseUrl $BarBaseUrl `
        -CredentialFile $CredentialFile | Out-String
    if ($LASTEXITCODE -ne 0) { throw "BAR $Operation command failed." }
    return $json | ConvertFrom-Json
}

function Wait-ForBarTradeStatus(
    [string] $OrderId,
    [string[]] $ExpectedStatuses,
    [datetimeoffset] $Deadline) {
    do {
        $detail = Invoke-BarAdmin 'detail' $OrderId
        if ($ExpectedStatuses -contains [string]$detail.tradeStatus) {
            return $detail
        }
        Start-Sleep -Seconds 2
    } while ([datetimeoffset]::UtcNow -lt $Deadline)
    throw 'BAR order did not reach the required terminal status before the case deadline.'
}

try {
    $offersResponse = Invoke-MainRequest 'GET' '/api/user/membership-plan-offers'
    Require-Status $offersResponse @(200) 'membership offers'
    $offers = @(($offersResponse.body | ConvertFrom-Json).offers)
    if ($offers.Count -eq 0) { throw 'Selected account has no legal higher personal tier.' }
    $selectedOffer = if ($TargetTier -eq 'NEXT') {
        $offers[0]
    } else {
        @($offers | Where-Object { [string]$_.targetTier -eq $TargetTier }) | Select-Object -First 1
    }
    if ($null -eq $selectedOffer) { throw "Target tier $TargetTier is not currently purchasable." }

    $idempotencyKey = [guid]::NewGuid().ToString()
    $createResponse = Invoke-MainRequest 'POST' '/api/user/membership-orders' @{
        targetTier = [string]$selectedOffer.targetTier
        payType = $PayType
        idempotencyKey = $idempotencyKey
    }
    Require-Status $createResponse @(201) 'BAR membership order creation'
    $order = $createResponse.body | ConvertFrom-Json
    $orderId = [string]$order.orderId
    $createdAt = [datetimeoffset]$order.createdAt
    $expiresAt = [datetimeoffset]$order.expiresAt
    $hardCloseAt = $expiresAt.AddMinutes(5)

    $attemptResponse = Invoke-MainRequest 'POST' "/api/user/membership-orders/$orderId/payment-attempts"
    Require-Status $attemptResponse @(201) 'first BAR Payment Attempt'
    $attempt = $attemptResponse.body | ConvertFrom-Json
    if ($null -eq $attempt.checkoutSubmission) {
        throw 'BAR Payment Attempt did not return checkoutSubmission.'
    }
    $submitExpiry = [datetimeoffset]$attempt.checkoutSubmission.submitExpiresAt
    if ($submitExpiry -gt $expiresAt -or $submitExpiry -le [datetimeoffset]::UtcNow) {
        throw 'BAR checkoutSubmission validity is outside the local order boundary.'
    }

    $anchor = switch ($ActionAnchor) {
        'CREATED' { $createdAt }
        'EXPIRES' { $expiresAt }
        'HARD_CLOSE' { $hardCloseAt }
    }
    $actionAt = $anchor.AddSeconds($ActionOffsetSeconds)
    Wait-Until $actionAt
    $barOperations = [System.Collections.Generic.List[object]]::new()

    switch ($Action) {
        'PAY' {
            $barOperations.Add((Invoke-BarAdmin 'pay-and-notify' $orderId))
        }
        'PAY_REPLAY_NOTIFY' {
            $barOperations.Add((Invoke-BarAdmin 'pay-and-notify' $orderId))
            for ($index = 0; $index -lt $NotifyReplayCount; $index++) {
                $barOperations.Add((Invoke-BarAdmin 'notify-retry' $orderId))
            }
        }
        'CANCEL' {
            $cancel = Invoke-MainRequest 'POST' "/api/user/membership-orders/$orderId/cancel"
            Require-Status $cancel @(200) 'BAR membership order cancel'
        }
        'CANCEL_THEN_PAY' {
            $cancel = Invoke-MainRequest 'POST' "/api/user/membership-orders/$orderId/cancel"
            Require-Status $cancel @(200) 'BAR membership order cancel before late pay'
            Start-Sleep -Seconds $PayAfterCancelSeconds
            $barOperations.Add((Invoke-BarAdmin 'pay-and-notify' $orderId))
        }
        'PAY_EXPECT_REJECTED' {
            $rejected = $false
            try {
                $null = Invoke-BarAdmin 'pay-and-notify' $orderId
            } catch {
                $rejected = $true
                # 这里只保留拒绝类型，不把远端响应正文或管理员会话信息写入浸泡产物。
                $barOperations.Add([ordered]@{
                    operation = 'pay-and-notify'
                    outcome = 'REJECTED'
                    errorType = $_.Exception.GetType().FullName
                })
            }
            if (-not $rejected) {
                throw 'BAR unexpectedly accepted payment after its own terminal boundary.'
            }
        }
        'UNPAID' { }
    }

    $deadline = switch ($ExpectedStatus) {
        'PAID' { [datetimeoffset]::UtcNow.AddMinutes(3) }
        'CANCELLED' { [datetimeoffset]::UtcNow.AddMinutes(3) }
        'CLOSED' { $hardCloseAt.AddMinutes(3) }
    }
    $finalOrder = Wait-ForOrderStatus $orderId $ExpectedStatus $deadline
    $barDetail = if ($Action -eq 'CANCEL_THEN_PAY') {
        Wait-ForBarTradeStatus $orderId @('REFUNDED') ([datetimeoffset]::UtcNow.AddMinutes(3))
    } elseif ($Action -eq 'PAY_EXPECT_REJECTED') {
        Wait-ForBarTradeStatus $orderId @('CLOSED', 'EXPIRED') ([datetimeoffset]::UtcNow.AddMinutes(1))
    } else {
        Invoke-BarAdmin 'detail' $orderId
    }

    $evidence = [ordered]@{
        verdict = 'PASS'
        caseName = $CaseName
        userId = [string]$row.userId
        idempotencyKey = $idempotencyKey
        orderId = $orderId
        targetTier = [string]$selectedOffer.targetTier
        payType = $PayType
        action = $Action
        actionAt = $actionAt.ToUniversalTime().ToString('O')
        expectedStatus = $ExpectedStatus
        actualStatus = [string]$finalOrder.status
        createdAt = $createdAt.ToUniversalTime().ToString('O')
        expiresAt = $expiresAt.ToUniversalTime().ToString('O')
        hardCloseAt = $hardCloseAt.ToUniversalTime().ToString('O')
        submitExpiresAt = $submitExpiry.ToUniversalTime().ToString('O')
        barOperations = @($barOperations)
        barFinal = $barDetail
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    }
    $parent = Split-Path -Parent $OutputFile
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputFile -Encoding UTF8
    $evidence | ConvertTo-Json -Compress -Depth 5
} finally {
    # Access Token 只保留在本进程局部变量；case 产物和控制台输出均不包含 Token。
    $accessToken = $null
    $headers = $null
}
