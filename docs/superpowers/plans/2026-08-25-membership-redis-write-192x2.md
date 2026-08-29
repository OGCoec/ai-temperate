# Membership Redis Write 192×2/384 Implementation Plan

> **状态：历史计划。** 新运行已由
> [`64×6/384` 设计](../specs/2026-08-26-membership-redis-write-64x6-design.md)
> 取代；本文只用于解释旧结果与回滚目标，不得作为当前生产或压测合同。

> **For Codex:** Execute incrementally and preserve the existing dirty worktree. Do not reset or modify unrelated user changes.

**Goal:** 将会员订单 Redis 写入边界统一调整为 Pipeline 192、lane 2、最多两个 Pipeline 批次同时在途、总逻辑在途 384，同时保持 HTTP 并发 256。

**Architecture:** 保留现有“稳定散列到 lane + 每 lane 单 Worker”的协调器结构，仅扩大受控批量和全局公平许可。所有启动器、采样器和证据合同显式携带并验证 384，防止只改 YAML 导致运行漂移。

**Tech Stack:** Java 21、Spring Boot ConfigurationProperties、JUnit 5、PowerShell 7。

### Task 1: 锁定新边界合同

**Files:**
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/config/MembershipPaymentRedisWritePropertiesTest.java`
- Modify: `ai-temperate-service/src/test/java/com/example/temperate/service/user/membership/payment/store/impl/MembershipOrderSnapshotWriteCoordinatorImplTest.java`
- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/loadtest/MembershipPaymentLoadtestProfileYamlTest.java`
- Modify: `loadtest/scripts/tests/*.ps1` 中相关配置合同

写入 `192/2/384` 的边界、越界拒绝、单 lane 最大 192 和运行证据漂移失败断言。

### Task 2: 修改生产配置与协调器说明

**Files:**
- Modify: `MembershipPaymentRedisWriteProperties.java`
- Modify: `MembershipOrderSnapshotWriteCoordinatorImpl.java`
- Modify: `application.yml`
- Modify: `application-loadtest-realtime.yml`

将默认值和合法上限改为 192/384，并更新中文并发不变量说明；协调器算法保持不变。

### Task 3: 贯通编排与采样

**Files:**
- Modify: `Start-MembershipLoadtestApplication.ps1`
- Modify: `Start-MembershipMillisecondBoundarySuite.ps1`
- Modify: `Start-MembershipSchedulerIndexHikariRetest.ps1`
- Modify: `Start-MembershipOrderCreateOptimizationRetest.ps1`
- Modify: `Invoke-Membership*Child.ps1`
- Modify: `Measure-MembershipPayment*Evidence.ps1`

显式传递 `RedisWriteMaximumInflight=384`，并让采样器验证 `inflight + availablePermits = 384`。

### Task 4: 验证

运行相关 Java 配置/协调器测试和 PowerShell 合同测试，确认不存在残留的正式 `128/256` Redis 写入合同。不得启动正式压测或修改正式数据，除非用户随后明确要求。
