# AI 会话三分之一输出上限预扣实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仅在 AI 会话预扣阶段使用模型最大输出 Token 的三分之一，降低调用门槛，同时保持上游真实最大输出和最终实际 Usage 结算不变。

**Architecture:** `AiConversationQuotaCalculator` 在预扣公式内部把完整 `maxOutputTokens` 转换为向上取整的三分之一预扣输出量；调用方、模型快照和上游 `maxCompletionTokens` 继续保存并使用完整模型上限。实际结算仍使用上游返回的完整 `prompt/cached/completion` Usage，超过预扣时沿用现有补扣或待对账流程。

**Tech Stack:** Java 21、Spring Boot 3.5、BigDecimal、JUnit 5、AssertJ、Maven。

---

## 行为契约

预扣公式固定为：

```text
reservationOutputTokens = ceil(modelMaxOutputTokens / 3)

reservedMinor = ceil(
    (
        estimatedPromptTokens * inputRatio
        + reservationOutputTokens * outputRatio
    )
    * 100 / 80000
)
```

最终结算公式保持不变：

```text
actualMinor = ceil(
    (
        uncachedPromptTokens * inputRatio
        + cachedPromptTokens * cachedInputRatio
        + completionTokens * outputRatio
    )
    * 100 / 80000
)
```

以 `maxOutputTokens=128000`、`estimatedPromptTokens=57`、输入倍率 `0.75`、输出倍率 `4.5` 为例：

```text
reservationOutputTokens = ceil(128000 / 3) = 42667
reservedMinor = ceil((57 * 0.75 + 42667 * 4.5) * 100 / 80000)
              = 241 minor
              = 2.41 额度
```

上游请求仍携带 `maxCompletionTokens=128000`。若真实费用低于 `241 minor`，现有结算事务退回差额；若真实费用高于 `241 minor`，现有结算事务补扣差额；补扣后余额会变成负数时继续进入 `RECONCILE_REQUIRED`。

## 文件范围

- 修改：`ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/billing/impl/AiConversationQuotaCalculator.java`
  - 定义固定预扣输出除数。
  - 使用无溢出的整数向上取整得到预扣输出 Token。
  - 只改变 `reservedQuota(...)`，不改变 `actualQuota(...)`。
- 修改：`ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/billing/impl/AiConversationQuotaCalculatorTest.java`
  - 覆盖三分之一预扣和完整实际结算。
- 修改：`ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/billing/impl/AiConversationBillingServiceImplTest.java`
  - 覆盖 Free 余额预扣和余额不足边界。
- 修改：`docs/operations/user-profile-cache-and-model-catalog.md`
  - 记录三分之一仅用于预扣、上游上限和实际结算不受影响。
- 不修改：`SpringAiCliProxyConversationModelClient.streamOptions(...)`
  - 必须继续把完整模型上限传给上游。
- 不修改数据库表、Redis、RabbitMQ、SSE 协议、前端接口或管理员模型字段。

### Task 1: 先更新计算器测试契约

**Files:**
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/billing/impl/AiConversationQuotaCalculatorTest.java`

- [ ] **Step 1: 把纯输出预扣用例改为三分之一上限**

```java
@Test
void reservationUsesOneThirdOfModelMaximumOutputRoundedUp() {
    long reserved = calculator.reservedQuota(
            0,
            128_000,
            new BigDecimal("0.75"),
            new BigDecimal("4.5"));

    assertThat(reserved).isEqualTo(241L);
}
```

- [ ] **Step 2: 保留输入参与预扣的用例并更新期望值**

```java
@Test
void reservationIncludesInputAfterReducingOnlyTheOutputCeiling() {
    long reserved = calculator.reservedQuota(
            100,
            128_000,
            new BigDecimal("0.75"),
            new BigDecimal("4.5"));

    assertThat(reserved).isEqualTo(241L);
    assertThat(reserved).isLessThan(5_000L);
}
```

- [ ] **Step 3: 新增实际结算不除以三的回归用例**

```java
@Test
void actualSettlementUsesTheFullReportedCompletionTokens() {
    long charged = calculator.actualQuota(
            0,
            0,
            128_000,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("4.5"));

    assertThat(charged).isEqualTo(720L);
}
```

- [ ] **Step 4: 经用户批准进入第二阶段后运行定向红灯测试**

Run:

```powershell
mvn -pl ai-temperate-service -am `
  -Dtest=AiConversationQuotaCalculatorTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 新预扣断言失败，实际结算完整计费断言继续通过；不得连接 PostgreSQL、Redis、RabbitMQ 或 CLIProxyAPI。

### Task 2: 实现三分之一预扣输出量

**Files:**
- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/billing/impl/AiConversationQuotaCalculator.java`

- [ ] **Step 1: 定义固定预扣输出除数**

```java
private static final long RESERVATION_OUTPUT_DIVISOR = 3L;
```

- [ ] **Step 2: 在非负校验后计算无溢出的向上取整三分之一**

```java
long reservationOutputTokens = maxOutputTokens
        / RESERVATION_OUTPUT_DIVISOR
        + (maxOutputTokens % RESERVATION_OUTPUT_DIVISOR == 0L ? 0L : 1L);
```

这里禁止使用 `(maxOutputTokens + 2) / 3`，因为 `Long.MAX_VALUE + 2` 会溢出。紧邻代码添加中文注释，说明该规则只放宽预扣门槛，不改变上游输出能力或最终结算。

- [ ] **Step 3: 仅把预扣输出乘数替换为三分之一结果**

```java
return toMinorUnits(
        BigDecimal.valueOf(estimatedPromptTokens).multiply(inputRatio)
                .add(BigDecimal.valueOf(reservationOutputTokens)
                        .multiply(outputRatio)));
```

- [ ] **Step 4: 经用户批准后重新运行计算器测试**

Run:

```powershell
mvn -pl ai-temperate-service -am `
  -Dtest=AiConversationQuotaCalculatorTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `AiConversationQuotaCalculatorTest` 全部通过，零成本、最小正成本和溢出保护保持通过。

### Task 3: 更新 Billing Service 预扣边界测试

**Files:**
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/billing/impl/AiConversationBillingServiceImplTest.java`

- [ ] **Step 1: 更新 Free 用户成功预扣期望**

```java
assertThat(reservation.reservedQuotaMinor()).isEqualTo(241L);
assertThat(fixture.quota().getQuotaBalanceMinor()).isEqualTo(4_759L);
```

- [ ] **Step 2: 更新余额不足边界**

```java
BillingFixture fixture = fixture(240L);
```

请求必须返回 `AI_QUOTA_INSUFFICIENT`，余额保持 `240L`，且 Usage 和 Usage Detail 不得写入。

- [ ] **Step 3: 经用户批准后运行 Billing 定向测试**

Run:

```powershell
mvn -pl ai-temperate-service -am `
  -Dtest=AiConversationBillingServiceImplTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `AiConversationBillingServiceImplTest` 全部通过，不连接外部基础设施。

### Task 4: 更新运维文档并做静态审查

**Files:**
- Modify: `docs/operations/user-profile-cache-and-model-catalog.md`

- [ ] **Step 1: 记录预扣与实际结算的不同输出口径**

文档必须明确：

```text
80000 加权 Token = 1.00 额度 = 100 minor。
预扣输出 Token = ceil(模型最大输出 Token / 3)。
实际上游最大输出不变，最终结算不除以三。
```

- [ ] **Step 2: 搜索错误地把实际结算或上游上限除以三的代码**

Run:

```powershell
rg -n "RESERVATION_OUTPUT_DIVISOR|reservationOutputTokens|maxCompletionTokens|actualQuota" `
  ai-temperate-service/src/main ai-temperate-service/src/test
```

Expected: 除以三只出现在 `reservedQuota(...)` 的预扣路径；`actualQuota(...)` 和 `streamOptions(...)` 不包含该规则。

- [ ] **Step 3: 代码评审检查**

确认以下约束：

```text
没有新增 YAML、数据库字段或环境变量。
没有修改模型缓存中的 128000 上限。
没有修改上游 maxCompletionTokens。
没有修改 SSE completed 数据或前端展示。
失败请求无 Usage 时未退款的问题不在本次改动中伪装修复。
```

### Task 5: 第二阶段验证与部署

**Files:**
- Verify only; no source changes expected.

- [ ] **Step 1: 向用户说明验证范围并取得明确批准**

验证只运行两个 Service 单元测试类和 Maven 编译，不连接生产 PostgreSQL、Redis、RabbitMQ、Cloudflare 或 CLIProxyAPI。

- [ ] **Step 2: 运行定向测试和编译**

Run:

```powershell
mvn -pl ai-temperate-service -am `
  -Dtest=AiConversationQuotaCalculatorTest,AiConversationBillingServiceImplTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl ai-temperate-web -am -DskipTests package
```

Expected: 两个定向测试类通过，后端模块编译和打包成功。

- [ ] **Step 3: 使用隔离测试用户验收成功路径**

数据库期望：

```text
reserved_quota_minor = 241
billing_status = 1 (SETTLED)
charged_quota_minor = 按真实 Usage 计算的值
settlement_delta_minor = charged_quota_minor - 241
```

对已验证过的 `prompt=32`、`completion=13` 示例，实际费用仍应为 `1 minor`，因此差额应为 `-240`。

- [ ] **Step 4: 部署并重启后端**

先停止新请求流量，部署新构建产物并重启 `ai-temperate-web`。恢复流量后检查新的 Usage Detail；历史预扣记录保持原值，不进行批量重算。

## 验收标准

```text
128000 最大输出的预扣计算使用 42667 Token。
示例预扣从 721 minor 降到 241 minor。
上游 maxCompletionTokens 仍为 128000。
最终实际结算继续使用全部真实 completionTokens。
成功调用能够多退少补，余额不足的补扣继续进入待对账。
没有数据库迁移、配置项或前端协议变更。
```

## 明确不在本次范围内

```text
不修复上游 429 无 Usage 时未自动退款的问题。
不调整各会员套餐每周总额度。
不改变 80000 固定换算基数。
不为不同模型配置不同的预扣比例。
不限制模型真正发送给上游的输出上限。
```
