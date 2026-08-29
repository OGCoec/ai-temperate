# Millisecond Boundary State Atomic Write Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent transient Windows readers of `soak-state.json` from terminating the millisecond-boundary Suite.

**Architecture:** Reuse the bounded atomic JSON publication pattern already used by the Master orchestrator. The Suite writes a complete temporary JSON file beside the target, atomically replaces the target, and retries only transient `IOException` sharing conflicts.

**Tech Stack:** PowerShell 7, .NET `System.IO.File`, repository PowerShell contract tests.

---

### Task 1: Lock the state publication contract

**Files:**
- Modify: `loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1`

- [x] Add assertions requiring `Write-AtomicJson`, a process-owned temporary path, `[IO.File]::Move(..., $true)`, 20 bounded attempts, 50ms retry delay, and `Save-State` delegation.
- [x] Add an assertion rejecting direct `Set-Content -LiteralPath $statePath` publication.
- [x] Run the contract test and confirm it fails because the Suite lacks atomic state publication.

Run:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1
```

Expected: FAIL identifying a missing atomic state publication fragment.

### Task 2: Implement bounded atomic publication

**Files:**
- Modify: `loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1:1073`

- [x] Add `Write-AtomicJson` using a same-directory temporary file.
- [x] Publish with `[IO.File]::Move($temporaryPath, $Path, $true)`.
- [x] Retry `[IO.IOException]` up to 20 attempts with 50ms delay and clean the temporary file in `finally`.
- [x] Change `Save-State` to pass its ordered value to `Write-AtomicJson`.

### Task 2.1: Harden Master publication and terminal state

**Files:**
- Modify: `loadtest/scripts/Start-MembershipOrderCreateOptimizationRetest.ps1`
- Modify: `loadtest/scripts/Start-MembershipSchedulerIndexHikariRetest.ps1`
- Modify: their orchestration contract tests

- [x] Apply the same 20 x 50ms atomic replacement contract to outer Master state files.
- [x] Suppress stale child PIDs in terminal Master states.
- [x] Classify exhausted Windows sharing conflicts as TEST_INVALID rather than functional failure.

### Task 3: Verify without running load

**Files:**
- Verify: `loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1`
- Verify: `loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1`

- [x] Re-run the relevant contract tests and require PASS.
- [x] Parse all modified PowerShell files with the PowerShell AST parser and require zero syntax errors.
- [x] Confirm the logging archive/delete function is unchanged.
- [ ] Do not start PostgreSQL, the application, JMeter, Canary, 40K or 80K.

No Git commit is performed because the shared workspace already contains user-owned uncommitted changes.
