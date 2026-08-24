[CmdletBinding()]
param(
    [string] $RunId = '',
    [string] $HostName = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int] $Port = 6655,
    [ValidateSet('http', 'https')]
    [string] $Protocol = 'http'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tokenRoot = Join-Path $repositoryRoot 'loadtest\local\millisecond-boundary'
$baseUrl = "$Protocol`://$HostName`:$Port"
$firstUserId = [int64]70000000000000000
$pageSize = 500
$pagesPerWave = 20
$usersPerWave = 10000
$waves = @(
    [pscustomobject]@{ Code = 'E-PRE'; FirstPage = 0 },
    [pscustomobject]@{ Code = 'E-AFTER'; FirstPage = 20 },
    [pscustomobject]@{ Code = 'H-PRE'; FirstPage = 40 },
    [pscustomobject]@{ Code = 'H-AFTER'; FirstPage = 60 }
)

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = 'membership-millisecond-boundary-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
}
if ($RunId -notmatch '^[A-Za-z0-9._-]+$') {
    throw 'RunId may contain only letters, digits, dot, underscore and hyphen.'
}
if ($Port -ne 6655 -or $HostName -ne '127.0.0.1') {
    throw 'Boundary Token issuance is fixed to the loopback 127.0.0.1:6655 application.'
}

$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -eq 6655 -and $_.LocalAddress -eq '127.0.0.1' })
if ($listeners.Count -ne 1) {
    throw 'Boundary Token issuance requires exactly one loopback listener on port 6655.'
}

$readiness = Invoke-WebRequest -UseBasicParsing `
    -Uri "$baseUrl/actuator/health/readiness" -TimeoutSec 15
if ($readiness.StatusCode -lt 200 -or $readiness.StatusCode -ge 300) {
    throw 'The boundary loadtest application is not ready.'
}

New-Item -ItemType Directory -Force -Path $tokenRoot | Out-Null
$completed = [Collections.Generic.List[object]]::new()

foreach ($wave in $waves) {
    $finalPath = Join-Path $tokenRoot "$RunId-$($wave.Code).csv"
    $partialPath = "$finalPath.partial"
    if (Test-Path -LiteralPath $finalPath) {
        throw "Refusing to overwrite an existing Token file: $finalPath"
    }

    $writer = $null
    try {
        # Token 只流式写入 Git 忽略目录；控制台仅输出页数和行数，禁止输出令牌内容。
        $writer = [IO.StreamWriter]::new(
            $partialPath,
            $false,
            [Text.UTF8Encoding]::new($false))
        $writer.WriteLine('"userId","accessToken"')
        $written = 0

        for ($pageOffset = 0; $pageOffset -lt $pagesPerWave; $pageOffset++) {
            $page = [int]$wave.FirstPage + $pageOffset
            $response = Invoke-RestMethod -Method Post `
                -Uri "$baseUrl/internal/test/membership-payments/millisecond-boundary/tokens/$page" `
                -TimeoutSec 60
            if ($response.Count -ne $pageSize) {
                throw "Token page $page returned $($response.Count) rows instead of $pageSize."
            }

            for ($index = 0; $index -lt $pageSize; $index++) {
                $expectedUserId = $firstUserId + ([int64]$page * $pageSize) + $index
                $actualUserId = [int64]$response[$index].userId
                $accessToken = [string]$response[$index].accessToken
                if ($actualUserId -ne $expectedUserId `
                        -or [string]::IsNullOrWhiteSpace($accessToken)) {
                    throw "Token page $page is empty, reordered or outside the fixed account range."
                }
                $escapedToken = $accessToken.Replace('"', '""')
                $writer.WriteLine(('"{0}","{1}"' -f $actualUserId, $escapedToken))
                $written++
            }

            if (($pageOffset + 1) % 25 -eq 0) {
                $writer.Flush()
                Write-Output (
                    'TOKEN_PROGRESS wave={0} pages={1}/{2} rows={3}/{4}' -f `
                    $wave.Code, ($pageOffset + 1), $pagesPerWave, $written, $usersPerWave)
            }
        }

        if ($written -ne $usersPerWave) {
            throw "Wave $($wave.Code) wrote $written rows instead of $usersPerWave."
        }
        $writer.Flush()
        $writer.Dispose()
        $writer = $null
        Move-Item -LiteralPath $partialPath -Destination $finalPath
        $completed.Add([pscustomobject]@{
            wave = $wave.Code
            path = $finalPath
            tokenCount = $written
        })
        Write-Output "TOKEN_WAVE_COMPLETE wave=$($wave.Code) rows=$written"
    } catch {
        if ($null -ne $writer) {
            $writer.Dispose()
        }
        if (Test-Path -LiteralPath $partialPath) {
            Remove-Item -LiteralPath $partialPath -Force
        }
        throw
    }
}

[pscustomobject]@{
    runId = $RunId
    tokenCount = ($completed | Measure-Object -Property tokenCount -Sum).Sum
    files = @($completed)
} | ConvertTo-Json -Depth 5
