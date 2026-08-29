# Membership Payment Scheduler, Paid Lookup Index, and Hikari Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 缩短五千条会员支付区段的回调与订单刷盘收敛时间，并让最近已支付订单查询稳定命中匹配索引，同时将用户确认的单实例 Hikari 压测上限固定为 96。

**Architecture:** Callback Worker 与订单持久化 Worker 各使用一个独立单线程 `TaskScheduler`，避免共享默认调度器互相阻塞；每轮仍按 100 条有界批处理，但最多运行 50 批以覆盖一个 5,000 条区段。`findLatestPaidOrder` 固定查询 PAID 状态并使用部分复合 B-tree 索引，索引键依次匹配两个等值条件与三列排序。Hikari 最大连接数为 96、常驻空闲连接为 8。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring TaskScheduler、MyBatis、PostgreSQL、HikariCP、JUnit 5、Testcontainers。

---

### Task 1: 锁定独立调度与五千条单轮容量

**Files:**
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/callback/PaymentCallbackFlushSchedulerTest.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/persistence/MembershipOrderPersistSchedulerTest.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentPropertiesBindingTest.java`
- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentSchedulingConfiguration.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/callback/PaymentCallbackFlushScheduler.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/persistence/MembershipOrderPersistScheduler.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentProperties.java`
- Modify: `ai-temperate-web/src/main/resources/application.yml`

- [ ] **Step 1: 编写失败测试**

  通过反射读取两个 `flush()` 方法的 `@Scheduled.scheduler`，要求分别使用 `membershipPaymentCallbackTaskScheduler` 和 `membershipPaymentOrderPersistTaskScheduler`；配置绑定测试要求两组 `maxBatchesPerRun()` 默认均为 50。

- [ ] **Step 2: 运行测试并确认旧实现失败**

  Run: `mvn -pl ai-temperate-service -am -Dtest=PaymentCallbackFlushSchedulerTest,MembershipOrderPersistSchedulerTest,MembershipPaymentPropertiesBindingTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: 实现最小调度隔离**

  新配置类提供两个独立、池大小为 1 的 `ThreadPoolTaskScheduler` Bean；两个 `@Scheduled` 分别通过 `scheduler` 属性绑定对应 Bean。把 Callback 与 OrderPersist 的 Java 默认值和 YAML 默认值统一改为 50，批次大小仍保持 100。

- [ ] **Step 4: 重新运行目标测试并确认通过**

  Run: `mvn -pl ai-temperate-service -am -Dtest=PaymentCallbackFlushSchedulerTest,MembershipOrderPersistSchedulerTest,MembershipPaymentPropertiesBindingTest -Dsurefire.failIfNoSpecifiedTests=false test`

### Task 2: 最近已支付订单查询与索引

**Files:**
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/membership/payment/MembershipPaymentPersistenceContractTest.java`
- Modify: `ai-temperate-mapper/src/test/java/com/example/temperate/mapper/user/membership/payment/MembershipPaymentMapperIntegrationTest.java`
- Modify: `ai-temperate-mapper/src/main/java/com/example/temperate/mapper/user/membership/payment/MembershipOrderMapper.java`
- Modify: `ai-temperate-mapper/src/main/resources/mapper/user/membership/payment/MembershipOrderMapper.xml`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/order/impl/MembershipOrderServiceImpl.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/offer/impl/MembershipPlanOfferServiceImpl.java`
- Modify: associated Mockito tests for the two callers
- Modify: `sql/018_create_membership_order.sql`
- Create: `sql/migrations/034_create_membership_order_latest_paid_index.sql`

- [ ] **Step 1: 编写失败契约与集成测试**

  契约测试要求查询使用固定 `status = 2`，迁移包含 `CREATE INDEX CONCURRENTLY` 且不包含事务块。集成测试准备足够的 PAID 历史订单、执行 `ANALYZE` 和 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`，要求计划包含新索引且不含显式 Sort。

- [ ] **Step 2: 运行测试并确认索引尚不存在**

  Run: `mvn -pl ai-temperate-mapper -am -Dtest=MembershipPaymentPersistenceContractTest,MembershipPaymentMapperIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: 实现固定 PAID 查询和部分复合索引**

  查询固定为 `status = 2`，移除多余 `paidStatus` 参数。新索引为：

  ```sql
  CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_membership_order_latest_paid
      ON membership_order (
          login_identity_id,
          membership_tier,
          paid_at DESC NULLS LAST,
          created_at DESC,
          id DESC
      )
      WHERE status = 2;
  ```

  固定字面量保证 PostgreSQL 使用服务端泛化预编译计划时仍能证明查询蕴含部分索引谓词。

- [ ] **Step 4: 更新两个调用方及 Mockito 契约**

  两个 Service 只传 `loginIdentityId` 与 `membershipTier`，不改变公开 Service 接口或业务裁决。

- [ ] **Step 5: 运行 Mapper 与 Service 目标测试**

  Run: `mvn -pl ai-temperate-service -am -Dtest=MembershipPaymentPersistenceContractTest,MembershipPaymentMapperIntegrationTest,MembershipOrderServiceImplTest,MembershipPlanOfferServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`

### Task 3: Hikari 96 配置合同

**Files:**
- Modify: `ai-temperate-web/src/main/resources/application.yml`
- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/user/membership/payment/config/MembershipPaymentConfigurationContractTest.java`

- [ ] **Step 1: 增加 YAML 合同断言**

  断言 `spring.datasource.hikari.maximum-pool-size` 默认值为 96，`minimum-idle` 默认值为 8，且两个配置均保留环境变量覆盖能力。

- [ ] **Step 2: 保留已确认配置**

  ```yaml
  maximum-pool-size: ${POSTGRES_POOL_MAXIMUM_SIZE:96}
  minimum-idle: ${POSTGRES_POOL_MINIMUM_IDLE:8}
  ```

- [ ] **Step 3: 运行 Web 配置合同测试**

  Run: `mvn -pl ai-temperate-web -am -Dtest=MembershipPaymentConfigurationContractTest -Dsurefire.failIfNoSpecifiedTests=false test`

### Task 4: 汇总验证

- [ ] **Step 1: 运行三组目标测试**

  分别运行 Service 调度测试、Mapper 索引测试和 Web YAML 合同测试，避免直接启动正式 40K 压测。

- [ ] **Step 2: 保存 EXPLAIN 关键证据**

  报告新索引名称、扫描类型、是否存在 Sort、执行时间和 buffers；如果数据规模太小导致优化器合理选择 Seq Scan，则扩大隔离测试数据，不使用 `enable_seqscan=off` 冒充真实命中。

- [ ] **Step 3: 检查差异边界**

  只报告本轮修改文件，保留工作树中用户已有的其他改动，不执行 reset、checkout 或正式压测。
