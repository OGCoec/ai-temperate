[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$gate = Join-Path $PSScriptRoot '..\Assert-MembershipJmeterResults.ps1'
if (-not (Test-Path -LiteralPath $gate)) {
    throw "Missing JMeter result gate: $gate"
}

$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'membership-jtl-gate-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    $header = 'timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage'
    $good = Join-Path $temporaryRoot 'good.jtl'
    @(
        $header,
        '1,10,Execute Real Millisecond Boundary Wave,200,OK,thread,text,true,'
    ) | Set-Content -LiteralPath $good -Encoding UTF8
    & $gate -Path $good -ExpectedSampleCount 1 `
        -ExpectedSamplerName 'Execute Real Millisecond Boundary Wave'

    $invalidRows = @(
        '1,10,Execute Real Millisecond Boundary Wave,200,OK,thread,text,false,script failed',
        '1,10,Execute Real Millisecond Boundary Wave,500,ERROR,thread,text,true,',
        '1,10,Execute Real Millisecond Boundary Wave,200,OK,thread,text,true,unexpected failure',
        '1,10,Unexpected Sampler,200,OK,thread,text,true,'
    )
    foreach ($row in $invalidRows) {
        $bad = Join-Path $temporaryRoot ('bad-' + [guid]::NewGuid().ToString('N') + '.jtl')
        @($header, $row) | Set-Content -LiteralPath $bad -Encoding UTF8
        $rejected = $false
        try {
            & $gate -Path $bad -ExpectedSampleCount 1 `
                -ExpectedSamplerName 'Execute Real Millisecond Boundary Wave'
        } catch {
            $rejected = $true
        }
        if (-not $rejected) {
            throw "JMeter result gate accepted an invalid row: $row"
        }
    }

    $duplicate = Join-Path $temporaryRoot 'duplicate.jtl'
    @(
        $header,
        '1,10,Execute Real Millisecond Boundary Wave,200,OK,thread,text,true,',
        '2,10,Execute Real Millisecond Boundary Wave,200,OK,thread,text,true,'
    ) | Set-Content -LiteralPath $duplicate -Encoding UTF8
    $rejected = $false
    try {
        & $gate -Path $duplicate -ExpectedSampleCount 1 `
            -ExpectedSamplerName 'Execute Real Millisecond Boundary Wave'
    } catch {
        $rejected = $true
    }
    if (-not $rejected) {
        throw 'JMeter result gate accepted an invalid sampler count.'
    }
} finally {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
}

Write-Output 'PASS: JMeter result gate rejects every failed or malformed sampler result.'
