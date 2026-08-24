# Membership Boundary 40k Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将会员支付边界套件缩减为 40,000 个固定账号、8×5,000 场景，并把两条会员队列固定为每队列 48 个消费者，同时保留两个 4,096 JMeter 并发和微秒证据合同。

**Architecture:** 账号范围由 `MembershipPaymentBoundaryLoadtestPolicy` 作为唯一 Java 事实来源，CSV/JMeter/PowerShell/SQL 使用相同的八区段静态合同。RabbitMQ 容量由监听容器工厂设置，Runner 在预检和每段稳定窗口同时核对消费者数量与 prefetch，避免配置与真实运行漂移。

**Tech Stack:** Java 21、Spring Boot、Spring AMQP、PostgreSQL、Redis、RabbitMQ、JMeter Groovy、PowerShell。

---

### Task 1: 固定账号策略缩容

**Files:**
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestPolicyTest.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryFixtureServiceImplTest.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestPolicy.java`
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/membership/payment/loadtest/impl/MembershipPaymentBoundaryFixtureServiceImpl.java`
- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestControllerTest.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/user/membership/payment/loadtest/MembershipPaymentBoundaryLoadtestController.java`

- [ ] **Step 1: 先把测试期望改为 40,000、80 页、每段 5,000、每套餐 1,250、每段 25 个 TEAM 探针。**
- [ ] **Step 2: 运行定向测试并确认仍因 400,000 常量失败。**
- [ ] **Step 3: 把策略常量改为 `TOTAL_USERS=40_000`、`GROUP_SIZE=5_000`、`USERS_PER_TIER=1_250`、`TEAM_PROBES_PER_GROUP=25`，八段起点依次为 0、5,000、10,000、15,000、20,000、25,000、30,000、35,000。**
- [ ] **Step 4: 把 reset 请求上限和夹具订单上限改为 40,000，并同步中文 JavaDoc。**
- [ ] **Step 5: 重新运行定向 Java 测试，期望全部 PASS。**

### Task 2: RabbitMQ 消费者改为 48

**Files:**
- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/user/membership/payment/config/MembershipPaymentConfigurationContractTest.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/user/membership/payment/config/MembershipPaymentRabbitConfiguration.java`
- Modify: `loadtest/scripts/Test-MembershipRabbitSingleConsumer.ps1`
- Modify: `loadtest/scripts/tests/Test-MembershipRabbitSingleConsumer.ps1`

- [ ] **Step 1: 把合同测试期望改为 `setConcurrentConsumers(48)`、`setMaxConcurrentConsumers(48)` 和 prefetch 20。**
- [ ] **Step 2: 运行合同测试并确认旧 32 配置导致失败。**
- [ ] **Step 3: 修改监听容器工厂和独立 Rabbit 检查脚本为精确 48 个消费者。**
- [ ] **Step 4: 重新运行 Java 与 PowerShell Rabbit 合同测试，期望全部 PASS。**

### Task 3: 8×5,000 JMeter 合同同步

**Files:**
- Modify: `loadtest/input/membership-millisecond-boundary-groups.csv`
- Modify: `loadtest/scripts/tests/Test-MembershipMillisecondBoundaryContract.ps1`
- Modify: `loadtest/scripts/Invoke-MembershipMillisecondBoundaryWave.ps1`
- Modify: `loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1`
- Modify: `loadtest/scripts/jmeter/membership-millisecond-boundary.groovy`
- Modify: `loadtest/sql/verify-membership-millisecond-boundary-wave.sql`
- Modify: `loadtest/sql/verify-membership-millisecond-boundary-final.sql`

- [ ] **Step 1: 先把 PowerShell 合同期望改为总数 40,000、每段 5,000、每套餐 1,250、TEAM 25、消费者 48，并保留两个 4,096 并发断言。**
- [ ] **Step 2: 运行合同测试，确认旧 CSV、Runner、Groovy 和 SQL 常量导致失败。**
- [ ] **Step 3: 更新八段用户范围、Token 计数、Latch、请求证据行数 `15,025`、合并计数、最终 SQL 聚合和 reset 数量。**
- [ ] **Step 4: 保留偏移循环 500、120 秒正式预检参数、60 秒区段间隔、微秒格式和有界 ConnectException 重试。**
- [ ] **Step 5: 重新运行 PowerShell 合同测试，期望输出 `PASS: 8x5,000 ...`。**

### Task 4: 构建、重启与运行基线

**Files:**
- Runtime artifact: `ai-temperate-web/target/ai-temperate-web-0.0.1-SNAPSHOT.jar`

- [ ] **Step 1: 运行已明确授权的定向测试和 Maven 打包，确认无编译错误。**
- [ ] **Step 2: 停止旧 6655 Java 实例，使用 `loadtest-realtime` 启动新 JAR，并确认只有一个 6655 监听且健康检查为 UP。**
- [ ] **Step 3: 调用受控 `/prepare`，确认身份、资料、额度和 FREE 均为 40,000，订单和回调均为 0。**
- [ ] **Step 4: 核对 Redis 会员支付 v1/v2 状态为空，两条队列各 48 个 active 消费者、prefetch 20，Ready/Unacked/DLQ 为 0。**
- [ ] **Step 5: 使用新 Run ID、120 秒预检、60 秒区段间隔、两个 4,096 并发启动正式 JMeter 套件并持续监听。**
