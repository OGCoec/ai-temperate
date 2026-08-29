[CmdletBinding()]
param(
    [string] $LoopbackBaseUrl = 'http://127.0.0.1:18080',
    [string] $UsersCsv = 'loadtest/local/loadtest-users.csv',
    [string] $PostgresUrl = '',
    [Parameter(Mandatory = $true)]
    [string] $OutputFile,
    [string] $BuildSha256 = '',
    [ValidateRange(30, 900)]
    [int] $GenerationTimeoutSeconds = 300
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$usersPath = if ([IO.Path]::IsPathRooted($UsersCsv)) {
    $UsersCsv
} else {
    Join-Path $repoRoot $UsersCsv
}
$baseUrl = $LoopbackBaseUrl.TrimEnd('/')
if ($baseUrl -notmatch '^http://(?:127\.0\.0\.1|localhost|\[::1\]):\d+$') {
    throw 'Quota first-use Controller probe requires an HTTP loopback base URL with an explicit port.'
}
if ([string]::IsNullOrWhiteSpace($PostgresUrl)) {
    $PostgresUrl = if (-not [string]::IsNullOrWhiteSpace(
            $env:MEMBERSHIP_PAYMENT_POSTGRES_URL)) {
        $env:MEMBERSHIP_PAYMENT_POSTGRES_URL
    } elseif (-not [string]::IsNullOrWhiteSpace($env:POSTGRES_URL)) {
        $env:POSTGRES_URL
    } else {
        'postgresql://postgres@127.0.0.1:5431/ai_temperate'
    }
}
if ($null -eq (Get-Command psql -ErrorAction SilentlyContinue)) {
    throw 'psql is required for authoritative quota verification.'
}
$tokens = @(Import-Csv -LiteralPath $usersPath)
$expectedUsers = @(
    72659006262480896L, 73014701344296960L,
    74891801495998464L, 76721355290185728L,
    84736921162616832L, 84739559597936640L,
    84742296792338432L, 84745417706835968L,
    84746552547086336L, 84753114204344320L,
    84754367089086464L, 84755204414771200L,
    84758509811535872L, 84758866549673984L,
    84759380653903872L, 84760794662834176L
)
if ($tokens.Count -ne $expectedUsers.Count) {
    throw 'Quota first-use Controller probe requires exactly sixteen token rows.'
}
for ($index = 0; $index -lt $expectedUsers.Count; $index++) {
    if ([long]$tokens[$index].userId -ne $expectedUsers[$index] `
            -or [string]::IsNullOrWhiteSpace([string]$tokens[$index].accessToken)) {
        throw "Quota first-use token row $index does not match the fixed account assignment."
    }
}

$cases = [System.Collections.Generic.List[object]]::new()
$apiKeyId = $null
$apiKeySecret = $null
$apiKeyVersion = $null
$failureMessage = $null
$verdict = 'FAIL'
$startedAt = [datetimeoffset]::UtcNow

function Get-QuotaState([long] $UserId) {
    $sql = @"
SELECT json_build_object(
    'userId', login_identity_id,
    'membershipTier', membership_tier,
    'membershipExpiresAt', membership_expires_at,
    'quotaBalanceMinor', quota_balance_minor,
    'quotaPeriodStartedAt', quota_period_started_at,
    'quotaPeriodEndsAt', quota_period_ends_at,
    'rowVersion', row_version
)::text
FROM user_membership_quota
WHERE login_identity_id = $UserId;
"@
    $raw = @(& psql -w $PostgresUrl -At -v ON_ERROR_STOP=1 -c $sql 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'Authoritative quota query failed.'
    }
    $line = @($raw | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    if ($line.Count -ne 1) {
        throw "Expected one quota row for fixed test user $UserId."
    }
    return ([string]$line[0] | ConvertFrom-Json)
}

function Quota-Fingerprint($State) {
    return @(
        [string]$State.membershipTier,
        [string]$State.membershipExpiresAt,
        [string]$State.quotaBalanceMinor,
        [string]$State.quotaPeriodStartedAt,
        [string]$State.quotaPeriodEndsAt,
        [string]$State.rowVersion
    ) -join '|'
}

function Assert-QuotaUnchanged($Before, $After, [string] $CaseName) {
    if ((Quota-Fingerprint $Before) -ne (Quota-Fingerprint $After)) {
        throw "$CaseName unexpectedly changed membership quota state."
    }
}

function Assert-FirstUseActivated($Before, $After, [string] $CaseName) {
    if ($null -ne $Before.quotaPeriodStartedAt `
            -and -not [string]::IsNullOrWhiteSpace(
                    [string]$Before.quotaPeriodStartedAt)) {
        throw "$CaseName did not begin from an unactivated quota period."
    }
    if ($null -eq $After.quotaPeriodStartedAt `
            -or [string]::IsNullOrWhiteSpace(
                    [string]$After.quotaPeriodStartedAt)) {
        throw "$CaseName did not activate the quota period."
    }
    $periodStart = [datetimeoffset][string]$After.quotaPeriodStartedAt
    $periodEnd = [datetimeoffset][string]$After.quotaPeriodEndsAt
    if ($periodEnd -ne $periodStart.AddDays(7)) {
        throw "$CaseName did not create an exact seven-day quota period."
    }
    if ([long]$After.quotaBalanceMinor -ge [long]$Before.quotaBalanceMinor) {
        throw "$CaseName did not charge the first successful request."
    }
}

function New-SessionHeaders([int] $UserIndex) {
    return @{
        Authorization = "Bearer $($tokens[$UserIndex].accessToken)"
        Accept = 'application/json, text/event-stream'
    }
}

function Get-HeaderValue($Headers, [string] $Name) {
    $value = $Headers[$Name]
    if ($null -eq $value) { return '' }
    return [string](@($value)[0])
}

function Wait-Generation(
        [int] $UserIndex,
        [string] $GenerationId,
        [string] $CaseName) {
    $deadline = [datetimeoffset]::UtcNow.AddSeconds($GenerationTimeoutSeconds)
    while ([datetimeoffset]::UtcNow -lt $deadline) {
        $response = Invoke-WebRequest `
            -UseBasicParsing `
            -Uri "$baseUrl/api/ai/conversations/generations/$GenerationId" `
            -Headers (New-SessionHeaders $UserIndex) `
            -SkipHttpErrorCheck `
            -TimeoutSec 30
        if ([int]$response.StatusCode -ne 200) {
            throw "$CaseName generation status returned HTTP $([int]$response.StatusCode)."
        }
        $state = $response.Content | ConvertFrom-Json
        if ([string]$state.status -eq 'SETTLED') {
            return $state
        }
        if ([string]$state.status -in @('REFUNDED', 'RECONCILE_REQUIRED')) {
            throw "$CaseName generation terminated as $([string]$state.status)."
        }
        Start-Sleep -Seconds 1
    }
    throw "$CaseName generation did not settle before the bounded deadline."
}

function Invoke-H5Generation(
        [int] $UserIndex,
        [string] $IdempotencyKey,
        [hashtable] $Body,
        [string] $CaseName,
        [int[]] $ExpectedStatus = @(200)) {
    $headers = New-SessionHeaders $UserIndex
    $headers['Idempotency-Key'] = $IdempotencyKey
    $response = Invoke-WebRequest `
        -UseBasicParsing `
        -Method POST `
        -Uri "$baseUrl/api/ai/conversations/responses" `
        -Headers $headers `
        -ContentType 'application/json' `
        -Body ($Body | ConvertTo-Json -Depth 12 -Compress) `
        -SkipHttpErrorCheck `
        -TimeoutSec $GenerationTimeoutSeconds
    if ([int]$response.StatusCode -notin $ExpectedStatus) {
        throw "$CaseName returned unexpected HTTP $([int]$response.StatusCode)."
    }
    if ([int]$response.StatusCode -ne 200) {
        return [pscustomobject]@{
            statusCode = [int]$response.StatusCode
            generationId = ''
            terminalStatus = ''
        }
    }
    $generationId = Get-HeaderValue $response.Headers 'X-AI-Generation-Id'
    if ($generationId -notmatch '^[A-Za-z0-9_-]{22}$') {
        throw "$CaseName did not return a canonical generation ID."
    }
    $terminal = Wait-Generation $UserIndex $generationId $CaseName
    return [pscustomobject]@{
        statusCode = 200
        generationId = $generationId
        terminalStatus = [string]$terminal.status
    }
}

function New-TextBody($Model) {
    return @{
        modelPublicId = [string]$Model.publicId
        reasoningEffortLevel = [int]$Model.defaultReasoningEffortLevel
        webSearchMode = 'OFF'
        input = @{ text = 'W16 controlled first-use text probe.'; attachments = @() }
    }
}

function New-ImageBody($Model) {
    return @{
        modelPublicId = [string]$Model.publicId
        reasoningEffortLevel = [int](@($Model.supportedImageGenerationLevels)[0])
        webSearchMode = 'OFF'
        image = @{
            aspect = [string](@($Model.supportedImageAspects)[0])
            outputCount = 1
        }
        input = @{ text = 'W16 one-pixel image probe.'; attachments = @() }
    }
}

function New-VideoBody($Model) {
    $modes = @($Model.supportedVideoModes | ForEach-Object { [string]$_ })
    if ($modes -notcontains 'TEXT_TO_VIDEO') {
        throw 'W16 requires a TEXT_TO_VIDEO xAI model without input attachments.'
    }
    return @{
        modelPublicId = [string]$Model.publicId
        reasoningEffortLevel = [int]$Model.defaultReasoningEffortLevel
        webSearchMode = 'OFF'
        video = @{
            mode = 'TEXT_TO_VIDEO'
            durationSeconds = [int]$Model.videoDuration.minimumSeconds
            resolution = [string](@($Model.supportedVideoResolutions)[0])
            aspectRatio = [string](@($Model.supportedVideoAspectRatios)[0])
            inputAttachmentPublicIds = @()
        }
        input = @{ text = 'W16 controlled video transfer probe.'; attachments = @() }
    }
}

function Add-Case(
        [string] $Name,
        [long] $UserId,
        [string] $Result,
        $Before,
        $After,
        [hashtable] $Extra = @{}) {
    $entry = [ordered]@{
        name = $Name
        userId = $UserId
        result = $Result
        beforeBalanceMinor = [long]$Before.quotaBalanceMinor
        afterBalanceMinor = [long]$After.quotaBalanceMinor
        beforePeriodStartedAt = $Before.quotaPeriodStartedAt
        afterPeriodStartedAt = $After.quotaPeriodStartedAt
        afterPeriodEndsAt = $After.quotaPeriodEndsAt
    }
    foreach ($key in $Extra.Keys) { $entry[$key] = $Extra[$key] }
    $cases.Add([pscustomobject]$entry)
}

function Safe-Error([string] $Message) {
    if ([string]::IsNullOrWhiteSpace($Message)) { return 'Unknown W16 probe failure.' }
    return $Message `
        -replace '(?i)Bearer\s+[^\s,;]+', 'Bearer <redacted>' `
        -replace '(?i)sk-[A-Za-z0-9_-]+', 'sk-<redacted>'
}

try {
    $catalogResponse = Invoke-WebRequest `
        -UseBasicParsing `
        -Uri "$baseUrl/api/ai-models?pageNum=1&pageSize=50" `
        -Headers (New-SessionHeaders 2) `
        -SkipHttpErrorCheck `
        -TimeoutSec 30
    if ([int]$catalogResponse.StatusCode -ne 200) {
        throw "AI model catalog returned HTTP $([int]$catalogResponse.StatusCode)."
    }
    $models = @(($catalogResponse.Content | ConvertFrom-Json).models)
    $textModel = $models | Where-Object {
        [string]$_.vendor -match '^(?i:openai|xai)$' `
            -and @($_.capabilities | ForEach-Object { [string]$_ }) `
                -contains 'CHAT_COMPLETIONS'
    } | Select-Object -First 1
    $imageModel = $models | Where-Object {
        [string]$_.vendor -match '^(?i:openai|xai)$' `
            -and @($_.capabilities | ForEach-Object { [string]$_ }) `
                -contains 'IMAGE_GENERATION' `
            -and @($_.supportedImageGenerationLevels).Count -gt 0 `
            -and @($_.supportedImageAspects).Count -gt 0
    } | Select-Object -First 1
    $videoModel = $models | Where-Object {
        [string]$_.vendor -match '^(?i:xai)$' `
            -and @($_.capabilities | ForEach-Object { [string]$_ }) `
                -contains 'VIDEO_GENERATION' `
            -and $null -ne $_.videoDuration `
            -and @($_.supportedVideoResolutions).Count -gt 0 `
            -and @($_.supportedVideoAspectRatios).Count -gt 0
    } | Select-Object -First 1
    if ($null -eq $textModel -or $null -eq $imageModel -or $null -eq $videoModel) {
        throw 'W16 requires enabled OpenAI/xAI text, image, and xAI video models.'
    }

    foreach ($userIndex in @(1, 2, 3, 10)) {
        $preflightQuota = Get-QuotaState ([long]$tokens[$userIndex].userId)
        if ($null -ne $preflightQuota.quotaPeriodStartedAt `
                -and -not [string]::IsNullOrWhiteSpace(
                        [string]$preflightQuota.quotaPeriodStartedAt)) {
            throw "W16 paid user index $userIndex does not have an unactivated quota period."
        }
    }

    # 创建响应中的完整 API Key 只保留在本进程变量，证据文件只记录软删除结果。
    $createHeaders = New-SessionHeaders 1
    $createHeaders['Idempotency-Key'] = [guid]::NewGuid().ToString()
    $createResponse = Invoke-WebRequest `
        -UseBasicParsing `
        -Method POST `
        -Uri "$baseUrl/api/users/me/api-keys" `
        -Headers $createHeaders `
        -ContentType 'application/json' `
        -Body (@{
            expiresAt = [datetimeoffset]::UtcNow.AddHours(2).ToString('O')
            modelPublicIds = @([string]$textModel.publicId)
        } | ConvertTo-Json -Compress) `
        -SkipHttpErrorCheck `
        -TimeoutSec 30
    if ([int]$createResponse.StatusCode -ne 201) {
        throw "Temporary API Key creation returned HTTP $([int]$createResponse.StatusCode)."
    }
    $createdKey = $createResponse.Content | ConvertFrom-Json
    $apiKeyId = [string]$createdKey.id
    $apiKeySecret = [string]$createdKey.apiKey
    $apiKeyVersion = [long]$createdKey.rowVersion
    if ($apiKeyId -notmatch '^[A-Za-z0-9_-]{26}$' `
            -or [string]::IsNullOrWhiteSpace($apiKeySecret)) {
        throw 'Temporary API Key response is invalid.'
    }

    $apiKeyReady = $false
    $apiKeyReadyDeadline = [datetimeoffset]::UtcNow.AddSeconds(30)
    while ([datetimeoffset]::UtcNow -lt $apiKeyReadyDeadline) {
        $modelResponse = Invoke-WebRequest `
            -UseBasicParsing `
            -Uri "$baseUrl/v1/models" `
            -Headers @{ Authorization = "Bearer $apiKeySecret"; Accept = 'application/json' } `
            -SkipHttpErrorCheck `
            -TimeoutSec 10
        if ([int]$modelResponse.StatusCode -eq 200) {
            $grantedNames = @((($modelResponse.Content | ConvertFrom-Json).data) |
                    ForEach-Object { [string]$_.id })
            if ($grantedNames -contains [string]$textModel.modelName) {
                $apiKeyReady = $true
                break
            }
        }
        Start-Sleep -Seconds 1
    }
    if (-not $apiKeyReady) {
        throw 'Temporary API Key did not become authoritative before the bounded deadline.'
    }

    $apiBefore = Get-QuotaState ([long]$tokens[1].userId)
    $apiResponse = Invoke-WebRequest `
        -UseBasicParsing `
        -Method POST `
        -Uri "$baseUrl/v1/chat/completions" `
        -Headers @{ Authorization = "Bearer $apiKeySecret"; Accept = 'application/json' } `
        -ContentType 'application/json' `
        -Body (@{
            model = [string]$textModel.modelName
            messages = @(@{ role = 'user'; content = 'W16 API Key first-use probe.' })
            max_tokens = 8
            stream = $false
        } | ConvertTo-Json -Depth 8 -Compress) `
        -SkipHttpErrorCheck `
        -TimeoutSec 60
    if ([int]$apiResponse.StatusCode -ne 200) {
        throw "API Key first-use call returned HTTP $([int]$apiResponse.StatusCode)."
    }
    $apiAfter = Get-QuotaState ([long]$tokens[1].userId)
    Assert-FirstUseActivated $apiBefore $apiAfter 'API_KEY_TEXT'
    Add-Case 'API_KEY_TEXT' ([long]$tokens[1].userId) 'SETTLED' $apiBefore $apiAfter

    $modelDeniedBefore = Get-QuotaState ([long]$tokens[1].userId)
    $modelDenied = Invoke-WebRequest `
        -UseBasicParsing `
        -Method POST `
        -Uri "$baseUrl/v1/chat/completions" `
        -Headers @{ Authorization = "Bearer $apiKeySecret"; Accept = 'application/json' } `
        -ContentType 'application/json' `
        -Body (@{
            model = 'w16-not-granted-model'
            messages = @(@{ role = 'user'; content = 'must not reserve quota' })
            max_tokens = 8
        } | ConvertTo-Json -Depth 8 -Compress) `
        -SkipHttpErrorCheck `
        -TimeoutSec 30
    if ([int]$modelDenied.StatusCode -notin @(400, 403, 404)) {
        throw "MODEL_NOT_ALLOWED returned unexpected HTTP $([int]$modelDenied.StatusCode)."
    }
    $modelDeniedAfter = Get-QuotaState ([long]$tokens[1].userId)
    Assert-QuotaUnchanged $modelDeniedBefore $modelDeniedAfter 'MODEL_NOT_ALLOWED'
    Add-Case 'MODEL_NOT_ALLOWED' ([long]$tokens[1].userId) `
        "HTTP_$([int]$modelDenied.StatusCode)" $modelDeniedBefore $modelDeniedAfter

    $textBody = New-TextBody $textModel
    $textIdempotency = [guid]::NewGuid().ToString()
    $h5Before = Get-QuotaState ([long]$tokens[2].userId)
    $h5Result = Invoke-H5Generation 2 $textIdempotency $textBody 'H5_TEXT'
    $h5After = Get-QuotaState ([long]$tokens[2].userId)
    Assert-FirstUseActivated $h5Before $h5After 'H5_TEXT'
    Add-Case 'H5_TEXT' ([long]$tokens[2].userId) $h5Result.terminalStatus `
        $h5Before $h5After @{ generationId = $h5Result.generationId }

    $replayBefore = Get-QuotaState ([long]$tokens[2].userId)
    $replayResult = Invoke-H5Generation 2 $textIdempotency $textBody 'IDEMPOTENT_REPLAY'
    $replayAfter = Get-QuotaState ([long]$tokens[2].userId)
    Assert-QuotaUnchanged $replayBefore $replayAfter 'IDEMPOTENT_REPLAY'
    if ($replayResult.generationId -ne $h5Result.generationId) {
        throw 'IDEMPOTENT_REPLAY returned a different generation.'
    }
    Add-Case 'IDEMPOTENT_REPLAY' ([long]$tokens[2].userId) 'NO_ADDITIONAL_CHARGE' `
        $replayBefore $replayAfter @{ generationId = $replayResult.generationId }

    $imageBefore = Get-QuotaState ([long]$tokens[3].userId)
    $imageResult = Invoke-H5Generation 3 ([guid]::NewGuid().ToString()) `
        (New-ImageBody $imageModel) 'IMAGE'
    $imageAfter = Get-QuotaState ([long]$tokens[3].userId)
    Assert-FirstUseActivated $imageBefore $imageAfter 'IMAGE'
    Add-Case 'IMAGE' ([long]$tokens[3].userId) $imageResult.terminalStatus `
        $imageBefore $imageAfter @{ generationId = $imageResult.generationId }

    $videoBefore = Get-QuotaState ([long]$tokens[10].userId)
    $videoResult = Invoke-H5Generation 10 ([guid]::NewGuid().ToString()) `
        (New-VideoBody $videoModel) 'VIDEO'
    $videoAfter = Get-QuotaState ([long]$tokens[10].userId)
    Assert-FirstUseActivated $videoBefore $videoAfter 'VIDEO'
    Add-Case 'VIDEO' ([long]$tokens[10].userId) $videoResult.terminalStatus `
        $videoBefore $videoAfter @{ generationId = $videoResult.generationId }

    $failedBefore = Get-QuotaState ([long]$tokens[11].userId)
    $invalidHeaders = @{
        Authorization = 'Bearer invalid-loadtest-token'
        Accept = 'application/json'
        'Idempotency-Key' = [guid]::NewGuid().ToString()
    }
    $failedResponse = Invoke-WebRequest `
        -UseBasicParsing `
        -Method POST `
        -Uri "$baseUrl/api/ai/conversations/responses" `
        -Headers $invalidHeaders `
        -ContentType 'application/json' `
        -Body ($textBody | ConvertTo-Json -Depth 10 -Compress) `
        -SkipHttpErrorCheck `
        -TimeoutSec 30
    if ([int]$failedResponse.StatusCode -notin @(401, 403)) {
        throw "FAILED authentication probe returned HTTP $([int]$failedResponse.StatusCode)."
    }
    $failedAfter = Get-QuotaState ([long]$tokens[11].userId)
    Assert-QuotaUnchanged $failedBefore $failedAfter 'FAILED'
    Add-Case 'FAILED' ([long]$tokens[11].userId) `
        "HTTP_$([int]$failedResponse.StatusCode)" $failedBefore $failedAfter

    $rollbackBefore = Get-QuotaState ([long]$tokens[11].userId)
    $rollbackStateBefore = Invoke-RestMethod `
        -Method GET `
        -Uri "$baseUrl/internal/test/membership-payments/inference-stub/controls/quota-rollback" `
        -TimeoutSec 30
    $arm = Invoke-RestMethod `
        -Method POST `
        -Uri ("$baseUrl/internal/test/membership-payments/inference-stub/controls/quota-rollback/arm?userId=" `
                + [string]$tokens[11].userId) `
        -TimeoutSec 30
    if (-not [bool]$arm.armed) { throw 'Quota rollback fault was not armed.' }
    $rollbackResult = Invoke-H5Generation 11 ([guid]::NewGuid().ToString()) `
        $textBody 'TRANSACTION_ROLLBACK' @(500, 503)
    $rollbackStateAfter = Invoke-RestMethod `
        -Method GET `
        -Uri "$baseUrl/internal/test/membership-payments/inference-stub/controls/quota-rollback" `
        -TimeoutSec 30
    $rollbackAfter = Get-QuotaState ([long]$tokens[11].userId)
    Assert-QuotaUnchanged $rollbackBefore $rollbackAfter 'TRANSACTION_ROLLBACK'
    if ([bool]$rollbackStateAfter.armed `
            -or [long]$rollbackStateAfter.failureCount `
                    -ne ([long]$rollbackStateBefore.failureCount + 1L)) {
        throw 'Quota rollback fault did not produce exactly one consumed failure.'
    }
    Add-Case 'TRANSACTION_ROLLBACK' ([long]$tokens[11].userId) `
        "HTTP_$($rollbackResult.statusCode)_ROLLED_BACK" $rollbackBefore $rollbackAfter

    $verdict = 'PASS'
} catch {
    $failureMessage = Safe-Error $_.Exception.Message
} finally {
    $apiKeyDeleted = $false
    if (-not [string]::IsNullOrWhiteSpace([string]$apiKeyId) `
            -and $null -ne $apiKeyVersion) {
        try {
            $deleteHeaders = New-SessionHeaders 1
            $deleteHeaders['If-Match'] = '"v' + [string]$apiKeyVersion + '"'
            $deleteResponse = Invoke-WebRequest `
                -UseBasicParsing `
                -Method DELETE `
                -Uri "$baseUrl/api/users/me/api-keys/$apiKeyId" `
                -Headers $deleteHeaders `
                -SkipHttpErrorCheck `
                -TimeoutSec 30
            $apiKeyDeleted = [int]$deleteResponse.StatusCode -eq 204
            if (-not $apiKeyDeleted) {
                $verdict = 'FAIL'
                $failureMessage = 'Temporary API Key cleanup did not return HTTP 204.'
            }
        } catch {
            $verdict = 'FAIL'
            $failureMessage = 'Temporary API Key cleanup failed.'
        }
    }
    $apiKeySecret = $null
    $outputParent = Split-Path -Parent $OutputFile
    if (-not [string]::IsNullOrWhiteSpace($outputParent)) {
        New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
    }
    [ordered]@{
        verdict = $verdict
        source = 'W16_CONTROLLER_PROBE'
        jarSha256 = $BuildSha256
        cases = @($cases | ForEach-Object { [string]$_.name })
        details = @($cases)
        temporaryApiKeyDeleted = $apiKeyDeleted
        failure = $failureMessage
        startedAt = $startedAt.ToString('O')
        completedAt = [datetimeoffset]::UtcNow.ToString('O')
    } | ConvertTo-Json -Depth 10 |
        Set-Content -LiteralPath $OutputFile -Encoding UTF8
}

if ($verdict -ne 'PASS') {
    throw "W16 quota first-use Controller probe failed: $failureMessage"
}
Get-Content -Raw -LiteralPath $OutputFile | ConvertFrom-Json
