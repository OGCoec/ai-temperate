# Membership Payment PostgreSQL 384 / Hikari 256 Retest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parameterize and execute the formal 40K membership-payment retest with PostgreSQL 384, Hikari 256/8, and a declared read-only Navicat observer.

**Architecture:** Keep application YAML defaults unchanged and pass the new connection contract only to the owned load-test JVM. Make the orchestrator, runtime sampler, verdict module, and report consume one frozen contract so runtime evidence cannot be labelled with obsolete 100/96 limits. Restart only PostgreSQL 5431, then run the existing formal suite unchanged at 8 × 5,000.

**Tech Stack:** PowerShell 7, Java 21/Spring Boot, HikariCP, PostgreSQL 18, Redis 7, RabbitMQ 4.2, Maven/JUnit 5.

---

### Task 1: Lock the new orchestration contract with failing tests

**Files:**
- Modify: `loadtest/scripts/tests/Test-MembershipSchedulerIndexHikariRetestOrchestration.ps1`
- Modify: `loadtest/scripts/tests/Test-MembershipSchedulerIndexHikariVerdict.ps1`
- Modify: `loadtest/scripts/tests/Test-MembershipSchedulerIndexHikariReport.ps1`
- Modify: `loadtest/scripts/tests/Test-MembershipRuntimeEvidenceSamplerProcessContract.ps1`

- [ ] **Step 1: Assert the formal defaults are PostgreSQL 384 and Hikari 256/8**

Add static assertions for `PostgresMaxConnections = 384`, `HikariMaximumPoolSize = 256`, `HikariMinimumIdle = 8`, the `hikari256-pg384` run prefix, and explicit propagation into the application launcher and report.

- [ ] **Step 2: Assert the verdict accepts expected limits as parameters**

Call `Get-MembershipHikariSpecialVerdict` with `-ExpectedMaximumPoolSize 256 -ExpectedMinimumIdle 8 -PostgresMaxConnections 384`. Verify total connections 383 remain eligible while 384 is unacceptable.

- [ ] **Step 3: Assert the report says Hikari 256 and consumes the frozen run manifest**

Create a fixture `run-manifest.json` containing the 384/256/8 contract and require both Markdown and JSON artifacts to identify Hikari 256.

- [ ] **Step 4: Assert PostgreSQL samples include Navicat observer facts**

Require CSV columns `navicat_total`, `navicat_active`, `navicat_idle_in_transaction`, and `navicat_write_or_ddl`.

- [ ] **Step 5: Run the four fixtures and verify RED**

Run each PowerShell test with `pwsh -NoProfile -File`. Expected: failures specifically identify obsolete 100/96 constants or missing Navicat fields.

### Task 2: Parameterize the application launcher and formal orchestrator

**Files:**
- Modify: `loadtest/scripts/Start-MembershipLoadtestApplication.ps1`
- Modify: `loadtest/scripts/Start-MembershipSchedulerIndexHikariRetest.ps1`

- [ ] **Step 1: Add validated launcher parameters**

Add `PostgresPoolMaximumSize` and `PostgresPoolMinimumIdle`, validate `minimumIdle <= maximumPoolSize`, export `POSTGRES_POOL_MAXIMUM_SIZE` and `POSTGRES_POOL_MINIMUM_IDLE` before `Start-Process`, and include both values in the non-secret startup JSON.

- [ ] **Step 2: Add the frozen formal contract parameters**

Use defaults 384, 256, 8, and a maximum of 8 Navicat observer connections. Change the default run prefix to `membership-payment-scheduler-index-hikari256-pg384-`.

- [ ] **Step 3: Replace the unrelated-connection gate with an observer-aware gate**

Reject unknown clients, Navicat counts above 8, `idle in transaction`, and write/DDL query text. Record allowed Navicat rows in `navicat-observer-baseline.json`.

- [ ] **Step 4: Propagate and freeze the values**

Pass Hikari 256/8 to the launcher, put all four expected values in `run-manifest.json`, validate the runtime endpoint against them, and pass the PostgreSQL ceiling to the evidence sampler.

- [ ] **Step 5: Run the orchestration fixture and verify GREEN**

Expected: `PASS: formal scheduler/index/Hikari retest orchestration contract is complete.`

### Task 3: Parameterize sampling, verdict, and report

**Files:**
- Modify: `loadtest/scripts/MembershipSchedulerIndexHikariEvidence.psm1`
- Modify: `loadtest/scripts/Measure-MembershipPaymentRuntimeEvidence.ps1`
- Modify: `loadtest/scripts/New-MembershipSchedulerIndexHikariReport.ps1`

- [ ] **Step 1: Extend the PostgreSQL watch row**

Emit total, active, waiting, Navicat total, Navicat active, Navicat idle-in-transaction, and a bounded write/DDL flag from the one persistent `psql` session. Update the converter to write stable CSV headers.

- [ ] **Step 2: Enforce runtime observer safety**

Stop sampling with preserved evidence when Navicat exceeds the configured budget, holds an open transaction, issues write/DDL, or total PostgreSQL connections reach 384.

- [ ] **Step 3: Parameterize Hikari classification**

Replace hardcoded 96/8 and 100 with required expected values. Keep timeout, pending, acquire P99, and database P99 classification semantics unchanged.

- [ ] **Step 4: Read the report contract from the run manifest**

Require the manifest fields, invoke the parameterized verdict, and generate titles and labels using Hikari 256 and PostgreSQL 384.

- [ ] **Step 5: Run sampler, verdict, and report fixtures and verify GREEN**

Expected: all relevant PowerShell fixtures print PASS.

### Task 4: Verify code and build a frozen artifact

**Files:**
- Verify only: Java and PowerShell sources changed in Tasks 1-3

- [ ] **Step 1: Run all evidence fixtures**

Run scheduler evidence, verdict, report, orchestration, runtime sampler process, and Redis exact-cleanup fixtures. Expected: zero failures.

- [ ] **Step 2: Run the original 43 targeted Java tests plus remediation tests**

Run the original eight classes together with `MembershipPaymentBoundaryFixtureServiceImplTest`, `MembershipPaymentBoundaryLoadtestControllerTest`, and `MembershipPaymentLoadtestRequestPolicyTest`. Expected: 66 tests, zero failures and zero errors.

- [ ] **Step 3: Package the application**

Run `mvn -pl ai-temperate-web -am "-DskipTests" package`. Expected: `BUILD SUCCESS`.

### Task 5: Reconfigure and restart PostgreSQL 5431

**Files:**
- Runtime state: `C:/Users/damn/Desktop/postgresql/data/postgresql.auto.conf`
- Evidence: new formal run directory

- [ ] **Step 1: Freeze the exact 5431 process and clean data baseline**

Verify the postmaster binary, data directory, port, zero membership orders/callbacks, 40,000 exact FREE quotas, and empty membership Redis/Rabbit state.

- [ ] **Step 2: Set max_connections**

Execute `ALTER SYSTEM SET max_connections = '384';` against port 5431.

- [ ] **Step 3: Restart only the 5431 postmaster**

Use `C:/Users/damn/Desktop/postgresql/bin/pg_ctl.exe restart -D C:/Users/damn/Desktop/postgresql/data`. Do not address or enumerate child processes for termination and do not touch port 5430.

- [ ] **Step 4: Verify effective configuration**

Require `SHOW max_connections` to equal 384, the server to listen on 127.0.0.1:5431, and the clean database baseline to remain unchanged.

### Task 6: Execute and supervise the formal 40K

**Files:**
- Runtime evidence: `loadtest-output/soak/membership-payment-scheduler-index-hikari256-pg384-<timestamp>/millisecond-boundary/`

- [ ] **Step 1: Start the formal orchestrator**

Use the new runId and defaults 384/256/8. Require a unique local application instance and sampler readiness before Suite start.

- [ ] **Step 2: Monitor continuously**

Poll orchestrator output and evidence state at intervals shorter than 60 seconds. Do not stop owned application or samplers until PASS, controlled failure, or a hard safety gate.

- [ ] **Step 3: Repair and rerun controlled failures**

Preserve the failed run, diagnose root cause, add a failing test, implement the minimal fix, rerun regression/build, precisely clean only the failed manifest, and start a new runId.

- [ ] **Step 4: Complete index probe and final report**

Require the real business query to use `idx_membership_order_latest_paid` without Sort/Seq Scan and generate the scheduler, index, Hikari 256, and overall verdict artifacts.

- [ ] **Step 5: Preserve evidence and formal data**

Stop only owned processes after reporting. Keep formal PostgreSQL, Redis, Rabbit evidence and 40K data until the user separately authorizes cleanup.
