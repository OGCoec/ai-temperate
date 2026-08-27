# Membership Full Reset Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a double-click BAT entry that safely clears all local Membership payment data, resets every membership quota row to zero/FREE, and removes all related Redis artifacts.

**Architecture:** A thin BAT launcher calls one PowerShell implementation beside it. The PowerShell script owns local-target validation, idle-system gates, a single PostgreSQL transaction, batched Redis `UNLINK`, post-clean verification, and exit reporting; a static offline contract verifies destructive scope without touching external services.

**Tech Stack:** Windows BAT, PowerShell 7, PostgreSQL `psql`, Docker CLI, Redis CLI in `redis7`, RabbitMQ CLI in `rabbitmq1`.

---

## File structure

- Create `loadtest/scripts/tests/Test-ResetAllMembershipDataContract.ps1`: offline source and launcher contract.
- Create `loadtest/scripts/Reset-AllMembershipData.ps1`: all preflight, database, Redis, and verification logic.
- Create `loadtest/scripts/Reset-AllMembershipData.bat`: double-click launcher and exit-code propagation.

### Task 1: Add the failing offline contract

**Files:**
- Create: `loadtest/scripts/tests/Test-ResetAllMembershipDataContract.ps1`
- Test: `loadtest/scripts/tests/Test-ResetAllMembershipDataContract.ps1`

- [ ] **Step 1: Write the failing test**

Create a PowerShell test that resolves the two production files and fails while they do not exist. Once present, read them as text and require:

```powershell
$requiredSql = @(
    'DELETE FROM membership_payment_callback',
    'DELETE FROM membership_order',
    'UPDATE user_membership_quota',
    'membership_tier = 0',
    'quota_balance_minor = 0',
    'quota_period_started_at = NULL',
    'quota_period_ends_at = NULL',
    'membership_expires_at = NULL')

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
```

Also require the exact local PostgreSQL URL, `redis7`, `rabbitmq1`, `UNLINK`, batch size 100, scan count 500, port/process/RabbitMQ gates, final verification and `RESET_COMPLETE`. Reject `Read-Host`, `TRUNCATE`, Redis `KEYS`, deletion of `ait:*:payment:order-persist:v[12]:lock:*`, and any BAT confirmation prompt.

- [ ] **Step 2: Run the test to verify RED**

Run:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\loadtest\scripts\tests\Test-ResetAllMembershipDataContract.ps1
```

Expected: nonzero exit with `Reset-AllMembershipData.ps1 does not exist.`

- [ ] **Step 3: Commit the RED contract**

```powershell
git add -- loadtest/scripts/tests/Test-ResetAllMembershipDataContract.ps1
git commit -m "test(loadtest): define full membership reset contract"
```

### Task 2: Implement the PowerShell reset engine

**Files:**
- Create: `loadtest/scripts/Reset-AllMembershipData.ps1`
- Test: `loadtest/scripts/tests/Test-ResetAllMembershipDataContract.ps1`

- [ ] **Step 1: Add fixed target and idle-system preflight**

Set strict mode and fixed targets:

```powershell
$postgresUrl = 'postgresql://postgres@127.0.0.1:5431/ai_temperate'
$redisContainer = 'redis7'
$rabbitContainer = 'rabbitmq1'
```

Resolve `psql` and `docker`; reject a listener on 6655 and Win32 process command lines containing active Membership Master, Scheduler, Suite, Wave, or JMeter drivers. Validate `current_database()`, server address and port, Redis `PING`, and zero ready/unacknowledged messages for every `membership.*` RabbitMQ queue.

- [ ] **Step 2: Add one PostgreSQL transaction**

Execute with `psql -X -w -v ON_ERROR_STOP=1 -q -A -t -F '|'`. The SQL must use data-modifying CTEs for callback deletion, order deletion and quota reset, emit their row counts, then raise before commit if either payment table is nonempty or any quota row is nonzero/non-null.

- [ ] **Step 3: Add bounded Redis cleanup**

Dot-source `MembershipBoundaryRedis.ps1`, scan only the twelve approved patterns with `--scan --pattern <pattern> --count 500`, deduplicate, and call `UNLINK` in batches of at most 100. Re-scan every pattern and fail on any remaining key.

- [ ] **Step 4: Emit final counts and errors**

On success print database counts, Redis counts and:

```powershell
Write-Host 'RESET_COMPLETE' -ForegroundColor Green
```

Wrap the main operation in `try/catch`, print `RESET_FAILED stage=<stage> message=<message>` in red, and exit 1 without hiding the original stage.

- [ ] **Step 5: Run the offline contract**

Run the Task 1 command. Expected at this intermediate point: failure only because the BAT launcher is still missing.

### Task 3: Add the double-click BAT launcher

**Files:**
- Create: `loadtest/scripts/Reset-AllMembershipData.bat`
- Test: `loadtest/scripts/tests/Test-ResetAllMembershipDataContract.ps1`

- [ ] **Step 1: Add the launcher**

Use `%~dp0` to locate the PS1, verify `pwsh` exists, invoke it with `-NoProfile -ExecutionPolicy Bypass -File`, preserve `%ERRORLEVEL%`, print success/failure, call `pause`, and `exit /b` with the preserved code. Do not use `set /p`, `choice`, or another confirmation mechanism.

- [ ] **Step 2: Run the contract to verify GREEN**

Run the Task 1 command. Expected:

```text
PASS: full Membership reset BAT and PowerShell contracts are complete.
```

- [ ] **Step 3: Commit implementation**

```powershell
git add -- loadtest/scripts/Reset-AllMembershipData.ps1 loadtest/scripts/Reset-AllMembershipData.bat loadtest/scripts/tests/Test-ResetAllMembershipDataContract.ps1
git commit -m "test(loadtest): add one-click membership full reset"
```

### Task 4: Verify without executing destructive operations

**Files:**
- Verify: `loadtest/scripts/Reset-AllMembershipData.ps1`
- Verify: `loadtest/scripts/tests/Test-ResetAllMembershipDataContract.ps1`

- [ ] **Step 1: Parse PowerShell syntax**

Use `[System.Management.Automation.Language.Parser]::ParseFile` for the implementation and contract. Expected: `PARSE_OK targetCount=2`.

- [ ] **Step 2: Re-run the offline contract**

Run the Task 1 command. Expected: PASS.

- [ ] **Step 3: Confirm no external execution**

Do not execute `Reset-AllMembershipData.bat` or `Reset-AllMembershipData.ps1`; do not connect to PostgreSQL, Redis, RabbitMQ, Java, or JMeter during implementation verification.
