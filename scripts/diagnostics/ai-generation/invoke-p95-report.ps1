[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [DateTimeOffset]$TestStartedAt
)

$ErrorActionPreference = 'Stop'

if ($env:AIT_CONFIRM_ISOLATED_PREPRODUCTION -ne 'YES_ISOLATED_NON_PRODUCTION') {
    throw 'P95 reporting is restricted to an explicitly confirmed isolated environment.'
}
if ($env:PGDATABASE -notmatch '(?i)(test|staging|preprod|preview|sandbox)') {
    throw 'PGDATABASE must visibly identify a non-production test database.'
}
if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    throw 'psql is required for P95 reporting.'
}

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..\..')
$query = Join-Path $repositoryRoot 'sql\checks\ai_conversation_generation_p95.sql'
$utcStart = $TestStartedAt.ToUniversalTime().ToString('O')

# 查询只读取测试开始时间之后的样本；PGPASSWORD 不会被打印或写入报告。
& psql -X -v ON_ERROR_STOP=1 -v "test_started_at=$utcStart" -f $query
if ($LASTEXITCODE -ne 0) {
    throw 'AI Generation P95 report failed.'
}
