# 会员支付 40,000 账号边界测试报告实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于已保留的正式 JMeter、PostgreSQL、Redis 与 RabbitMQ 证据，生成一份可审计的 40,000 账号、8×5,000 微秒边界测试 Markdown 报告。

**Architecture:** 报告只引用正式 Run `membership-millisecond-boundary-20260823-224500` 的落盘证据，并把运行时正式 PASS 与运行后确认的两项测试口径问题分开陈述。报告不重新执行压测、不修改测试数据，也不把退款侧 `provider_trade_no` 的原设计行为误报为产品缺陷。

**Tech Stack:** Markdown、JMeter JTL/CSV、PowerShell 证据汇总、PostgreSQL 最终验证文本、Redis/RabbitMQ JSON 快照。

---

### Task 1: 核对正式运行证据

**Files:**
- Read: `loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/run-manifest.json`
- Read: `loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/verdict.json`
- Read: `loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/final-timestamp-evidence.csv`
- Read: `loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/final-postgres-verification.txt`

- [x] **Step 1: 核对 Run ID、源码指纹、时间窗口、账号数和数据保留状态**

  预期：`verdict=PASS`、`actualOrders=40000`、`dataPreserved=true`。

- [x] **Step 2: 按八个区段重新统计裁决、订单终态、closing deadline 和实际接收区间**

  预期：每段 5,000；合计 `APPLIED=24987`、`REFUND_REQUIRED=15013`，回调与权益裁决差异均为 0。

- [x] **Step 3: 核对 HTTP、Redis 与 RabbitMQ 收敛证据**

  预期：每段 15,025 个逻辑请求全部满足预期；两条会员队列各 48 个消费者且 Ready/Unacked/DLQ 为 0；Redis 四个处理集合均为 0。

### Task 2: 编写正式 Markdown 报告

**Files:**
- Create: `docs/handoffs/2026-08-24-membership-millisecond-boundary-40k-jmeter-test-report.md`

- [x] **Step 1: 写明测试目标、环境、边界合同和证据范围**

  报告明确这是本机受控模拟回调，不连接真实第三方支付平台，并区分业务时间微秒精度与毫秒调度目标。

- [x] **Step 2: 写入八区段结果、终态汇总和漂移统计**

  报告按 `received_at` 与 `expiresAt/hardCloseAt` 的实际微秒关系解释 E/H 边界两侧的动态裁决，不机械要求固定数量。

- [x] **Step 3: 记录两项测试过程问题**

  第一项写为退款侧 `provider_trade_no` 验收口径误判及由此引入的错误回填修改；第二项写为 JMeter Groovy 小数微秒与 PostgreSQL `BIGINT` 验证器不兼容。不得把任何一项写成会员支付状态机缺陷。

- [x] **Step 4: 写明结论边界**

  正式 Run 在当时源码与验收合同下 PASS；边界裁决、终态和权益安全通过，但该 Run 不能证明退款订单 `provider_trade_no` 为空这一原设计字段合同，因为运行代码已经执行了回填。

### Task 3: 自检报告

**Files:**
- Verify: `docs/handoffs/2026-08-24-membership-millisecond-boundary-40k-jmeter-test-report.md`

- [x] **Step 1: 校验报告中的总数与分段合计**

  预期：八段行数合计 40,000，`24,987 + 15,013 = 40,000`。

- [x] **Step 2: 校验所有本地证据链接存在**

  预期：报告引用的 manifest、verdict、最终 SQL 输出、最终时间戳证据和八个区段目录均存在。

- [x] **Step 3: 检查敏感信息与结论准确性**

  预期：不包含 Access Token、密码或密钥；不宣称真实第三方支付、不宣称 Exactly Once，也不隐瞒测试后确认的字段口径偏差。
