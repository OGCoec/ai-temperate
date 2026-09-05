# Membership Terminal Entitlement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让所有未支付 CANCELLED/CLOSED 订单持久化为 NOT_GRANTED，并安全支持迟到付款迁移到 REFUND_REQUIRED。

**Architecture:** 在订单批量状态刷盘 SQL 中原子补齐无支付终态裁决；迟到付款继续由现有权益事务裁决，并仅放宽 NOT_GRANTED 到 REFUND_REQUIRED 的单向迁移。新增独立数据库迁移回填历史空值，公开 API、Redis Snapshot 与 RabbitMQ 消息保持不变。

**Tech Stack:** Java 21、Spring、MyBatis、PostgreSQL、JUnit 5、AssertJ、Testcontainers、PowerShell/JMeter。

---

### Task 1: 锁定数据库状态迁移契约

**Files:**
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/membership/payment/MembershipPaymentMapperIntegrationTest.java`
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/membership/payment/MembershipPaymentPersistenceContractTest.java`

- [ ] 增加失败测试：CANCELLED/CLOSED 批量刷盘后读取到 `NOT_GRANTED` 和对应时间。
- [ ] 增加失败测试：`NOT_GRANTED → REFUND_REQUIRED` 成功，`NOT_GRANTED → APPLIED` 被拒绝。
- [ ] 增加迁移文本契约，要求新枚举、约束和历史回填 SQL。
- [ ] 运行目标测试并确认修改前失败。

### Task 2: 实现 NOT_GRANTED 状态与 SQL 原子迁移

**Files:**
- Modify: `ai-temperate-model/src/main/java/com/example/temperate/model/user/membership/payment/MembershipOrderEntitlementResolution.java`
- Modify: `ai-temperate-mapper/src/main/resources/mapper/user/membership/payment/MembershipOrderMapper.xml`
- Modify: `sql/018_create_membership_order.sql`
- Modify: `sql/migrations/030_add_membership_order_entitlement_resolution.sql`
- Create: `sql/migrations/032_add_membership_order_not_granted_resolution.sql`

- [ ] 在枚举和约束中加入 `NOT_GRANTED`。
- [ ] 在 `batchAdvanceState` 同一 UPDATE 中，仅为未支付 CANCELLED/CLOSED 空裁决写入 `NOT_GRANTED` 与 snapshot `updatedAt`。
- [ ] 调整 `batchResolveEntitlements`，允许空值、相同值和 `NOT_GRANTED → REFUND_REQUIRED`，并在允许迁移时覆盖值和时间。
- [ ] 新迁移先替换检查约束，再精确回填历史终态空值。
- [ ] 运行 Mapper 契约和集成测试并确认通过。

### Task 3: 放宽迟到付款事务且保持 APPLIED 封闭

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/entitlement/impl/MembershipPaymentEntitlementSettlementServiceImpl.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/entitlement/MembershipPaymentEntitlementSettlementServiceImplTest.java`

- [ ] 增加失败测试：CANCELLED/NOT_GRANTED 可进入 refund settlement。
- [ ] 修改退款前置校验，仅允许现有裁决为空、REFUND_REQUIRED 或 NOT_GRANTED。
- [ ] 保留 APPLIED 校验：NOT_GRANTED 不得补发套餐。
- [ ] 运行权益结算目标测试并确认通过。

### Task 4: 更新浸泡验收与文档

**Files:**
- Modify: `loadtest/sql/verify-membership-soak-final.sql`
- Modify: `loadtest/sql/verify-membership-order-concurrency.sql`
- Modify: `docs/database/membership-payment-logical-relations.md`

- [ ] 最终扫描要求 CANCELLED/CLOSED 的裁决严格为 NOT_GRANTED 或 REFUND_REQUIRED。
- [ ] W04 并发测试要求无回调终态为 NOT_GRANTED。
- [ ] 文档记录状态语义与唯一允许覆盖路径。

### Task 5: 部署验证与正式重启

**Files:**
- Runtime artifacts only under `loadtest-output/`.

- [ ] 运行目标 Maven 测试和模块编译，预期 `BUILD SUCCESS`。
- [ ] 停止旧 6655 Java，应用迁移 032，构建并启动唯一 127.0.0.1:6655 实例；确认 8080 无监听。
- [ ] 用受控 fixture 再次验证 16/16 FREE。
- [ ] 保留旧 FAIL 证据，精确清理旧测试订单、callback、Redis 和 RabbitMQ 残留。
- [ ] 创建新 SoakId，从 W01 开始正式 24 小时浸泡，禁止合并旧构建结果。
