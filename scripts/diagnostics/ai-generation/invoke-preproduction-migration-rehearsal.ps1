[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

if ($env:AIT_CONFIRM_ISOLATED_PREPRODUCTION -ne 'YES_ISOLATED_NON_PRODUCTION') {
    throw 'Set AIT_CONFIRM_ISOLATED_PREPRODUCTION=YES_ISOLATED_NON_PRODUCTION before modifying an isolated database.'
}
if ($env:AI_CONVERSATION_ASYNC_GENERATION_ENABLED -eq 'true') {
    throw 'Migration rehearsal requires AI_CONVERSATION_ASYNC_GENERATION_ENABLED=false.'
}
foreach ($name in @('PGHOST', 'PGPORT', 'PGDATABASE', 'PGUSER')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Missing required isolated PostgreSQL variable: $name"
    }
}
if ($env:PGDATABASE -notmatch '(?i)(test|staging|preprod|preview|sandbox)') {
    throw 'PGDATABASE must visibly identify an isolated test, staging, preprod, preview, or sandbox database.'
}
if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    throw 'psql is required for the migration rehearsal.'
}

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..\..')
$generationSql = Join-Path $repositoryRoot 'sql\011_create_ai_conversation_generation.sql'
$payloadSql = Join-Path $repositoryRoot 'sql\012_create_ai_conversation_generation_payload.sql'
$schemaCheck = Join-Path $repositoryRoot 'sql\checks\ai_conversation_generation_schema.sql'
$orphanCheck = Join-Path $repositoryRoot 'sql\checks\ai_conversation_generation_orphans.sql'

# 密码只允许通过 PGPASSWORD 或 pgpass 进入 libpq，禁止拼进命令行和测试报告。
& psql -X -v ON_ERROR_STOP=1 -c "SELECT current_database(), current_user, inet_server_addr(), inet_server_port();"
if ($LASTEXITCODE -ne 0) { throw 'Isolated PostgreSQL identity check failed.' }
& psql -X -v ON_ERROR_STOP=1 -f $generationSql
if ($LASTEXITCODE -ne 0) { throw 'Generation migration failed.' }
& psql -X -v ON_ERROR_STOP=1 -f $payloadSql
if ($LASTEXITCODE -ne 0) { throw 'Generation payload migration failed.' }
& psql -X -v ON_ERROR_STOP=1 -f $schemaCheck
if ($LASTEXITCODE -ne 0) { throw 'Generation schema verification failed.' }
& psql -X -v ON_ERROR_STOP=1 -f $orphanCheck
if ($LASTEXITCODE -ne 0) { throw 'Generation orphan verification failed.' }

Write-Output 'AI Generation isolated migration rehearsal completed. No rollback or production connection was performed.'
