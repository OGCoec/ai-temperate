# API Key Model Usage Billing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PostgreSQL schemas for API Key model usage and its one-to-one precharge detail without adding cross-request idempotency or conversation payload storage.

**Architecture:** Store final metering and billing outcome in `ai_model_api_usage`, and store reservation evidence and settlement delta in `ai_model_api_usage_detail`. Keep all relationships logical, with supporting indexes, orphan checks, and a database relationship document.

**Tech Stack:** PostgreSQL SQL migrations, JUnit 5, AssertJ, Maven

---

### Task 1: Add the schema contract test

**Files:**
- Create: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/ai/AiModelApiUsagePersistenceContractTest.java`

- [ ] Add assertions for the two migrations, the one-to-one unique constraint, logical-relation indexes, rejected fields, and orphan checks.
- [ ] Do not run the test during phase one; after explicit phase-two approval run:

```powershell
mvn -pl ai-temperate-mapper -Dtest=AiModelApiUsagePersistenceContractTest test
```

Expected after the migrations exist: `BUILD SUCCESS` and all methods in `AiModelApiUsagePersistenceContractTest` pass.

### Task 2: Add the core usage schema

**Files:**
- Create: `sql/016_create_ai_model_api_usage.sql`
- Create: `sql/checks/ai_model_api_usage_orphans.sql`

- [ ] Create the identity primary key, API Key digest, model relation, billing state, final Token fields, final charge, terminal fields, timestamps, and minimum validation constraints.
- [ ] Add query-driven indexes for API Key history, model history, and pending reconciliation scans.
- [ ] Add Chinese comments for the table, columns, and indexes.
- [ ] Add bounded offline orphan-check SQL for missing API Key and model rows.

### Task 3: Add the one-to-one precharge detail schema

**Files:**
- Create: `sql/017_create_ai_model_api_usage_detail.sql`
- Create: `sql/checks/ai_model_api_usage_detail_orphans.sql`

- [ ] Create the identity primary key and unique logical `usage_id` relation.
- [ ] Store only vendor, stream mode, reserved quota, and settlement delta.
- [ ] Exclude idempotency, conversation, message, content, and pricing ratio fields.
- [ ] Add Chinese comments and bidirectional orphan checks.

### Task 4: Document the logical relationships

**Files:**
- Create: `docs/database/ai-model-api-usage-logical-relationship.md`

- [ ] Document transactional write order, delete order, orphan checks, recovery, and the accepted no-foreign-key risk.
- [ ] Review the final diff without running compilation, tests, migrations, or external services during phase one.

