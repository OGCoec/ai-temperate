# Membership 4,000-User Millisecond Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The current dirty worktree is user-owned; do not create a worktree, reset files, stage unrelated changes, or commit unless the user separately requests it.

**Goal:** Build and run an auditable 4,000-user local membership-payment test that exercises the millisecond immediately before, at, and after `expiresAt` and `hardCloseAt`, classifying every order by server `receivedAt`.

**Architecture:** Keep the existing sixteen-user soak path unchanged. Add a separately gated, loopback-only persistent fixed-range fixture and paged token path for `70000000000000000..70000000000003999`, then run four 1,000-order subwaves with a bounded asynchronous scheduler. Every 500-user group contains 125 GO, 125 PLUS, 125 PRO, and 125 MAX full-price purchases; each subwave also performs five TEAM rejection probes that must create no order. Verify every callback against PostgreSQL server timestamps, capture Redis/RabbitMQ evidence, and retain the 4,000 identity/profile/quota templates while removing only run-owned orders, callbacks, tokens, and cache/message residue.

**Tech Stack:** Java 21, Spring Boot, MyBatis, PostgreSQL, Redis, RabbitMQ, PowerShell 7, Apache JMeter/Groovy, JUnit 5, AssertJ, Mockito.

**Acceptance boundary:** JUnit/PowerShell contract tests validate only the harness. Every one of the 4,000 legal boundary orders, including `-1ms`, exact-boundary, `+1ms`, and 2ms progression cases, must be executed by JMeter through the real 6655 HTTP path and real Redis/RabbitMQ/Callback Worker/PostgreSQL chain. Harness tests never count toward the business verdict.

---

## File map

New Java units:

- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentBoundaryLoadtestProperties.java` — fixed feature gate; contains no arbitrary range input.
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryFixtureService.java` — prepare/state/reset contract for persistent templates.
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryFixtureState.java` — non-sensitive fixture counts.
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestPolicy.java` — canonical fixed range, page and group mapping.
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryTokenService.java` — fixed 500-token page contract.
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/impl/MembershipPaymentBoundaryFixtureServiceImpl.java` — transactional batch fixture lifecycle.
- `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/impl/MembershipPaymentBoundaryTokenServiceImpl.java` — validation plus fifteen-hour token issue.
- `ai-temperate-web/src/main/java/com/example/temperate/web/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestController.java` — loopback-only prepare/state/reset/token endpoints.

Modified Java/MyBatis units:

- `UserLoginIdentityMapper.java/.xml`, `UserProfileMapper.java/.xml`, `UserMembershipQuotaMapper.java/.xml` — fixed-size batch insert/count/template-validation and quota reset.
- `MembershipOrderMapper.java/.xml`, `MembershipPaymentCallbackMapper.java/.xml` — range counts and exact run-owned order/callback cleanup.
- `MembershipPaymentLoadtestAccessServiceImpl.java` — accept the fixed synthetic range only while the boundary gate is enabled.
- `MembershipPaymentLoadtestRequestPolicy.java` — recognize only the exact new loopback paths.
- `application-loadtest-realtime.yml` — disabled-by-default boundary gate with Chinese comments on every line.

New load-test units:

- `loadtest/input/membership-millisecond-boundary-groups.csv` — eight fixed 500-user group definitions.
- `loadtest/jmeter/membership-millisecond-boundary.jmx` — one orchestration sampler, not 4,000 sleeping JMeter threads.
- `loadtest/scripts/jmeter/membership-millisecond-boundary.groovy` — bounded creation, scheduling, callback, and evidence logic.
- `loadtest/scripts/Invoke-MembershipMillisecondBoundaryWave.ps1` — one 1,000-user subwave.
- `loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1` — prepare, four subwaves, aggregate, run-owned cleanup, persistent-template reset.
- `loadtest/sql/verify-membership-millisecond-boundary-wave.sql` — per-wave server-time verdict.
- `loadtest/sql/verify-membership-millisecond-boundary-final.sql` — 4,000-order aggregate verdict.
- `loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1` — static safety and cardinality contract.

## Task 1: Canonical fixed-range policy

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestPolicy.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestPolicyTest.java`

- [x] **Step 1: Write the failing policy test**

The test must require these invariants:

```java
assertThat(policy.firstUserId()).isEqualTo(70_000_000_000_000_000L);
assertThat(policy.lastUserId()).isEqualTo(70_000_000_000_003_999L);
assertThat(policy.totalUsers()).isEqualTo(4_000);
assertThat(policy.pageUserIds(0)).hasSize(500).startsWith(70_000_000_000_000_000L);
assertThat(policy.pageUserIds(7)).hasSize(500).endsWith(70_000_000_000_003_999L);
assertThatThrownBy(() -> policy.pageUserIds(-1)).isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> policy.pageUserIds(8)).isInstanceOf(IllegalArgumentException.class);
```

Also assert the eight group mappings and exact offset sequences `-1000,-998,...,-2` and `0,2,...,998`.

- [x] **Step 2: Run RED**

```powershell
mvn -pl ai-temperate-service -am `
  "-Dtest=MembershipPaymentBoundaryLoadtestPolicyTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: FAIL because the policy type does not exist.

- [x] **Step 3: Implement the immutable policy**

Use constants only; do not bind range, page size, group size, tier, or offset from HTTP/configuration. Expose `isBoundaryUser(long)`, `pageUserIds(int)`, and immutable group definitions.

- [x] **Step 4: Run GREEN and capture output**

Expected: `BUILD SUCCESS`, policy tests PASS.

- [x] **Step 5: Record the focused diff**

```powershell
git diff -- ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestPolicy.java `
  ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestPolicyTest.java
```

## Task 2: Disabled-by-default configuration gate

**Files:**
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentBoundaryLoadtestProperties.java`
- Modify: `ai-temperate-web/src/main/resources/application-loadtest-realtime.yml`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentBoundaryLoadtestPropertiesTest.java`
- Test: `ai-temperate-web/src/test/java/com/example/temperate/web/user/membership/payment/loadtest/MembershipPaymentLoadtestProfileYamlTest.java`

- [x] **Step 1: Write failing property and YAML contract tests**

Require one boolean property:

```text
app.membership-payment.boundary-loadtest.enabled
```

The YAML default must be `${MEMBERSHIP_PAYMENT_BOUNDARY_LOADTEST_ENABLED:false}` and every configuration line must have a directly preceding Chinese comment.

- [x] **Step 2: Run RED**

Run the two named test classes; expect missing binding/YAML failures.

- [x] **Step 3: Add the property record and YAML block**

Do not add configurable IDs, counts, ranges, tiers, TTLs, callback keys, or paths.

- [x] **Step 4: Run GREEN**

Expected: both tests PASS and Spring YAML parsing remains valid.

## Task 3: Batch Mapper primitives

**Files:**
- Modify: `ai-temperate-mapper/src/main/java/com/example/temperate/mapper/user/identity/UserLoginIdentityMapper.java`
- Modify: `ai-temperate-mapper/src/main/resources/mapper/user/identity/UserLoginIdentityMapper.xml`
- Modify: `ai-temperate-mapper/src/main/java/com/example/temperate/mapper/user/profile/UserProfileMapper.java`
- Modify: `ai-temperate-mapper/src/main/resources/mapper/user/profile/UserProfileMapper.xml`
- Modify: `ai-temperate-mapper/src/main/java/com/example/temperate/mapper/user/membership/UserMembershipQuotaMapper.java`
- Modify: `ai-temperate-mapper/src/main/resources/mapper/user/membership/UserMembershipQuotaMapper.xml`
- Modify: `MembershipOrderMapper.java/.xml`
- Modify: `MembershipPaymentCallbackMapper.java/.xml`
- Test: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/membership/payment/MembershipPaymentBoundaryFixtureMapperIntegrationTest.java`

- [x] **Step 1: Write failing integration tests**

In a rollback transaction insert two representative synthetic users through the new batch methods, then assert:

```text
identity/profile/quota insert counts = 2
range counts = 2
callback/order cleanup affects only the supplied run-owned orders
quota reset counts = 2
identity/profile/quota template counts remain 2
neighbor IDs outside the fixed range remain unchanged
```

- [x] **Step 2: Run RED**

Expected: compilation failure because the Mapper methods are absent.

- [x] **Step 3: Add batch methods**

Use parameterized `<foreach>` `VALUES`; service callers must submit exactly 1..500 records. Run-owned deletion is limited to this order:

```text
membership_payment_callback
membership_order
```

Do not delete `user_membership_quota`, `user_profile`, or `userloginidentity`. Add batch template validation and batch FREE quota reset. Do not add physical foreign keys. All range methods use the canonical start-inclusive/end-exclusive constants supplied by the service, never HTTP values.

- [x] **Step 4: Run GREEN**

Expected: mapper integration tests PASS; no N+1 SQL.

## Task 4: Transactional fixture lifecycle

**Files:**
- Create: `MembershipPaymentBoundaryFixtureService.java`
- Create: `MembershipPaymentBoundaryFixtureState.java`
- Create: `impl/MembershipPaymentBoundaryFixtureServiceImpl.java`
- Test: `MembershipPaymentBoundaryFixtureServiceImplTest.java`

- [x] **Step 1: Write failing service tests**

Cover:

```text
disabled gate rejects prepare/state/reset
first prepare creates all three template rows only when the fixed range is empty
subsequent prepare accepts only an exact 4,000-row deterministic template
prepare rejects partial, mismatched, or foreign fixed-range rows without overwriting them
first prepare writes eight batches of 500 in one PostgreSQL transaction
every generated email is unique and ends in .invalid
all quotas are FREE/full/unactivated
partial batch count rolls back everything
reset refuses active/unresolved orders
reset deletes only explicitly supplied run-owned callbacks/orders
reset restores all 4,000 quotas to FREE/full/unactivated and retains identity/profile/quota rows
prepare and reset invalidate only synthetic user caches after commit
```

- [x] **Step 2: Run RED**

Expected: missing service types.

- [x] **Step 3: Implement minimal service**

Use interface + stateless `Impl`, constructor injection, public transactional methods, Chinese JavaDoc and comments for transaction order and destructive scope. `state()` is read-only and returns counts only.

- [x] **Step 4: Run GREEN**

Expected: all fixture tests PASS.

## Task 5: Paged boundary Token service and AT-only authentication

**Files:**
- Create: `MembershipPaymentBoundaryTokenService.java`
- Create: `impl/MembershipPaymentBoundaryTokenServiceImpl.java`
- Modify: `MembershipPaymentLoadtestAccessServiceImpl.java`
- Test: `MembershipPaymentBoundaryTokenServiceImplTest.java`
- Test: `MembershipPaymentLoadtestAccessServiceImplTest.java`

- [x] **Step 1: Write failing tests**

Require:

```text
page 0..7 returns exactly the canonical 500 IDs in order
each page performs one bulk identity read and one bulk quota read
inactive/missing rows fail the entire page
disabled gate rejects issuing tokens
existing sixteen-user authentication remains unchanged
boundary IDs authenticate only while the boundary gate is enabled
valid signed Token for neighboring ID is rejected before DB read
```

- [x] **Step 2: Run RED**

Expected: missing service and boundary-auth failures.

- [x] **Step 3: Implement**

Issue fifteen-hour Access Tokens through the existing `AuthTokenService`; never log or persist them. Keep the existing `MembershipPaymentLoadtestProperties.allowedUserIds` maximum and sixteen-user list unchanged.

- [x] **Step 4: Run GREEN**

Expected: token and authentication tests PASS.

## Task 6: Loopback-only fixture and token controller

**Files:**
- Create: `MembershipPaymentBoundaryLoadtestController.java`
- Modify: `MembershipPaymentLoadtestRequestPolicy.java`
- Test: `MembershipPaymentBoundaryLoadtestControllerTest.java`
- Test: `MembershipPaymentLoadtestRequestPolicyTest.java`

- [x] **Step 1: Write failing web tests**

Exact routes:

```http
POST /internal/test/membership-payments/millisecond-boundary/prepare
GET  /internal/test/membership-payments/millisecond-boundary/state
POST /internal/test/membership-payments/millisecond-boundary/reset
POST /internal/test/membership-payments/millisecond-boundary/tokens/{page}
```

Require loopback, enabled property, page `0..7`, `Cache-Control: no-store`, no sensitive values in errors, and no arbitrary child paths.

- [x] **Step 2: Run RED**

Expected: route/controller missing.

- [x] **Step 3: Implement controller and exact request-policy matches**

Controller depends only on interfaces. Token response contains only the selected page. Prepare/state/reset responses contain counts, never emails, tokens, callback IDs, or full order IDs.

- [x] **Step 4: Run GREEN**

Expected: controller/policy tests PASS.

## Task 7: Static 8×500 contract and input

**Files:**
- Create: `loadtest/input/membership-millisecond-boundary-groups.csv`
- Create: `loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1`

- [ ] **Step 1: Write the failing PowerShell contract first**

Assert:

```text
exactly eight group rows
every group count is 500
total is 4,000
ID ranges are contiguous and non-overlapping
four PRE/AFTER subwaves each total 1,000
offset definitions exactly match the approved spec
every 500-user group contains exactly 125 GO, 125 PLUS, 125 PRO, and 125 MAX targets
four subwaves define exactly five TEAM rejection probes each
runner/JMX/Groovy/SQL files exist
no default port 8080
no loadtest-fast
no configurable arbitrary ID range
```

- [ ] **Step 2: Run RED**

```powershell
pwsh -NoProfile -File loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1
```

Expected: FAIL because input and runner artifacts do not exist.

- [ ] **Step 3: Add only the CSV**

Run again; expected failure advances to missing Runner/JMX/SQL, proving the contract is active.

## Task 8: Bounded asynchronous JMeter driver

**Files:**
- Create: `loadtest/jmeter/membership-millisecond-boundary.jmx`
- Create: `loadtest/scripts/jmeter/membership-millisecond-boundary.groovy`
- Test: extend `Test-MembershipMillisecondBoundaryContract.ps1`

- [ ] **Step 1: Add failing source-contract assertions**

Require `ScheduledThreadPoolExecutor`, bounded creation/HTTP pools, per-order absolute target calculation, synchronized evidence writes, 6655 default, and reject `loadtest-fast`.

- [ ] **Step 2: Run RED**

Expected: missing scheduler evidence.

- [ ] **Step 3: Implement the minimal driver**

One JMeter orchestration thread must:

```text
load exactly 1,000 Token rows for the selected subwave
first issue five TEAM rejection probes for the selected subwave and prove that no order is created
create one deterministic GO/PLUS/PRO/MAX order per user with bounded concurrency
start exactly one Payment Attempt per order
derive targetAt from each server expiresAt
schedule 1,000 callback tasks without sleeping 1,000 JMeter threads
send through the real local simulator callback HTTP endpoint
record dispatch/completion timing and response result
wait for terminal settlement through a bounded observation deadline
```

No boundary group may be replaced by a Java unit/integration test or direct database/cache mutation. The exact-boundary samples in the `0,2,...,998` groups are ordinary JMeter callback requests whose final classification is based on persisted server `receivedAt`.

HTTP pool size and creation concurrency are explicit Runner parameters with conservative defaults; the fixed group cardinality is not configurable.

- [ ] **Step 4: Run GREEN static contract**

Expected: contract PASS.

## Task 9: Per-wave SQL verdict

**Files:**
- Create: `loadtest/sql/verify-membership-millisecond-boundary-wave.sql`
- Test: extend `Test-MembershipMillisecondBoundaryContract.ps1`

- [ ] **Step 1: Add failing SQL contract assertions**

Require joins from runner evidence to orders and callbacks, and explicit comparisons:

```sql
callback.received_at < order.expires_at
callback.received_at < order.closing_deadline_at
callback.received_at >= order.closing_deadline_at
```

- [ ] **Step 2: Implement verifier**

The verifier imports the wave CSV into a temp table and checks exactly 1,000 unique users/orders. Expected resolution derives solely from server `received_at`:

```sql
CASE
  WHEN callback.received_at < order.closing_deadline_at THEN 'APPLIED'
  ELSE 'REFUND_REQUIRED'
END
```

It also verifies order status, order/callback entitlement resolution, quota tier, duplicate callbacks, active orders, and unresolved terminal rows. Export a redacted `server-time-verdict.csv` and a single PASS/FAIL result.

- [ ] **Step 3: Run static GREEN**

Expected: SQL contract PASS.

## Task 10: Wave orchestration and evidence

**Files:**
- Create: `loadtest/scripts/Invoke-MembershipMillisecondBoundaryWave.ps1`
- Modify: `loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1`

- [ ] **Step 1: Add failing orchestration assertions**

Require preflight for 6655/8080, source fingerprint, one Rabbit consumer each, exact Token page pair, JMeter exit, SQL, Redis, RabbitMQ, and no cleanup on failure.

- [ ] **Step 2: Implement one-wave Runner**

Artifacts:

```text
run-manifest.json
scenario-orders.csv
callback-dispatch.csv
server-time-verdict.csv
time-drift.csv
JTL
jmeter.log
sql-verification.txt
redis-before/after.json
rabbit-before/after.json
verdict.json
reproduce-command.txt
```

On failure, write `verdict=FAIL`, retain all evidence, stop subsequent waves, and do not remove run-owned evidence automatically. The persistent templates remain in place.

- [ ] **Step 3: Run static GREEN**

Expected: all orchestration assertions PASS.

## Task 11: Four-wave suite and final aggregate verifier

**Files:**
- Create: `loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1`
- Create: `loadtest/sql/verify-membership-millisecond-boundary-final.sql`
- Modify: `Test-MembershipMillisecondBoundaryContract.ps1`

- [ ] **Step 1: Add failing suite assertions**

Require order:

```text
prepare/reset FREE baseline -> two-minute precheck -> E-PRE -> E-AFTER -> H-PRE -> H-AFTER
-> aggregate verify -> durable report -> run-owned cleanup/reset -> post-reset verify
```

There is no arbitrary observation gap between waves; only terminal/infrastructure convergence gates.

- [ ] **Step 2: Implement suite**

Final SQL must prove 4,000 unique synthetic users, 4,000 legal orders, eight groups of 500, exactly 125 GO/PLUS/PRO/MAX orders in every group, 20 rejected TEAM probes with no additional order, no duplicate callback/provider transaction, no active order, no unresolved entitlement, and every server-time classification correct.

- [ ] **Step 3: Implement exact run-owned cleanup and persistent-template reset proof**

After the aggregate PASS is durably written, call the fixed reset endpoint with the suite-owned order manifest, remove eight local Token fragments, and verify: run-owned orders/callbacks and Redis artifacts are zero; identity/profile/quota template counts remain exactly 4,000; every quota is restored to FREE/full/unactivated. Preserve redacted reports.

- [ ] **Step 4: Run static GREEN**

Expected: contract PASS.

## Task 12: Targeted Java/PowerShell verification and compile

- [ ] **Step 1: Run all new targeted tests**

```powershell
mvn -pl ai-temperate-web -am `
  "-Dtest=MembershipPaymentBoundaryLoadtestPolicyTest,MembershipPaymentBoundaryLoadtestPropertiesTest,MembershipPaymentBoundaryFixtureMapperIntegrationTest,MembershipPaymentBoundaryFixtureServiceImplTest,MembershipPaymentBoundaryTokenServiceImplTest,MembershipPaymentLoadtestAccessServiceImplTest,MembershipPaymentBoundaryLoadtestControllerTest,MembershipPaymentLoadtestRequestPolicyTest,MembershipPaymentLoadtestProfileYamlTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

pwsh -NoProfile -File loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1
```

Expected: `BUILD SUCCESS` and PowerShell `PASS`.

- [ ] **Step 2: Compile the executable module**

```powershell
mvn -pl ai-temperate-web -am -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Confirm scope**

Inspect `git diff --name-only`; no unrelated user files may have changed.

## Task 13: Restart the isolated app and run the 4,000-user suite

- [ ] **Step 1: Preserve current evidence and capture source fingerprint**

Do not delete the completed 20+20 output or the failed historical formal soak.

- [ ] **Step 2: Restart only the 6655 application**

Enable `MEMBERSHIP_PAYMENT_BOUNDARY_LOADTEST_ENABLED=true`; prove 8080 closed and Rabbit consumers exactly one each.

- [ ] **Step 3: Run the suite**

```powershell
pwsh -NoProfile -File loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1 `
  -HostName 127.0.0.1 -Port 6655 -Protocol http -PrecheckSeconds 120
```

Expected: four subwave verdicts PASS, aggregate PASS, run-owned cleanup PASS, and persistent-template reset PASS.

- [ ] **Step 4: Audit final evidence**

Independently inspect manifests, JTL, SQL, Redis, RabbitMQ, drift distributions, tier cardinalities, TEAM rejection evidence, run-owned cleanup counts, and retained-template counts before claiming PASS.

## Task 14: Resume the full soak objective

- [ ] **Step 1: Freeze the resulting source fingerprint**

Any business/SQL/Lua/Runner-verifier change after this point invalidates formal aggregation.

- [ ] **Step 2: Run local W01 through W08 with no artificial gaps**

All waves must use the same source fingerprint and real 5+5 timing. Do not merge historical W01-W07 or independent W08S evidence into the new formal PASS.

- [ ] **Step 3: Execute the BAR deployment gate and W09 through W16**

Use only official BAR APIs and the external Chrome Extension where required. No BAR database updates, Computer Use, or in-app browser.

- [ ] **Step 4: Produce the final PASS/FAIL/BLOCKED audit**

Cross-check every requirement, named wave, artifact, invariant, persistent-template/reset proof, and source fingerprint before updating the active goal.
