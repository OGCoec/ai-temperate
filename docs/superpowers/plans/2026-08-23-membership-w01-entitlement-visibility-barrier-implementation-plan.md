# W01 Entitlement Visibility Barrier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent reused W01 accounts from freezing a now-illegal target tier before the previous PAID entitlement is visible.

**Architecture:** Keep the existing per-account semaphore and public API-only test boundary. Add a bounded post-PAID offer-visibility barrier before releasing the semaphore, then preserve the existing bounded order-creation retry for the independent PostgreSQL active-order release window.

**Tech Stack:** PowerShell contract tests, Groovy JMeter runner, Spring Boot public membership offer API.

---

### Task 1: Add the failing barrier contract

**Files:**
- Modify: `loadtest/scripts/tests/Test-MembershipStateMachineCreationBarrier.ps1`
- Test: `loadtest/scripts/tests/Test-MembershipStateMachineCreationBarrier.ps1`

- [ ] Require a named `waitForEntitlementVisibility` helper, a 60-second deadline, 250-millisecond polling, and a call after a `PAID/APPLIED` result.
- [ ] Run `pwsh -File loadtest/scripts/tests/Test-MembershipStateMachineCreationBarrier.ps1` and verify it fails because the helper is absent.

### Task 2: Implement the minimal public-API barrier

**Files:**
- Modify: `loadtest/scripts/jmeter/membership-state-machine-realtime.groovy`
- Test: `loadtest/scripts/tests/Test-MembershipStateMachineCreationBarrier.ps1`

- [ ] Extract the selected target tier into a local variable before creating the order.
- [ ] Add `waitForEntitlementVisibility(targetTier)` that polls `GET /api/user/membership-plan-offers` until `targetTier` is absent, with a 60-second deadline and 250-millisecond interval.
- [ ] Invoke the helper only when `expectedStatus == PAID` and `expectedResolution == APPLIED`, before leaving the semaphore-protected block.
- [ ] Run the contract test and verify `PASS`.

### Task 3: Re-run the affected wave

**Files:**
- Evidence: `loadtest-output/soak/<new-soak-id>/local/W01/**`

- [ ] Preserve the failed run evidence and stop its exact Runner/JMeter processes.
- [ ] Delete only the failed run's evidenced orders, callbacks, and Redis artifacts; restore all 16 fixed users to the FREE baseline.
- [ ] Start a new source-fingerprinted local soak on port 6655.
- [ ] Require the complete 30-case W01 verdict to be `PASS` before proceeding to W02.

### Task 4: Protect later waves from entitlement concentration

**Files:**
- Create: `loadtest/scripts/tests/Test-MembershipMarkerCreationBarrier.ps1`
- Create: `loadtest/scripts/tests/Test-MembershipSoakAccountOrdering.ps1`
- Create: `loadtest/scripts/tests/Test-MembershipCallbackRaceSecondaryAccount.ps1`
- Modify: `loadtest/scripts/jmeter/membership-marker-stage-matrix.groovy`
- Modify: `loadtest/scripts/Start-MembershipPaymentSoakLocalPhase.ps1`
- Modify: `loadtest/scripts/jmeter/membership-callback-race-idempotency.groovy`

- [ ] Observe all three new contract tests fail against the pre-fix scripts.
- [ ] Add the W02 account reuse barriers without changing Marker timing.
- [ ] Reorder the existing 16 Token rows by a read-only membership-tier query before each wave.
- [ ] Reserve the fifth ordered account for W03-B's secondary order.
- [ ] Run all four harness contract tests and require `PASS`.
