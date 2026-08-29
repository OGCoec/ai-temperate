# 会员支付 40,000 账号边界测试审计报告扩写设计

## 目标

把现有 `docs/handoffs/2026-08-24-membership-millisecond-boundary-40k-jmeter-test-report.md` 从结果型报告扩写为单文档审计报告，使不了解此前聊天和测试器实现的读者，也能独立理解测试为什么这样设计、八个区段实际发生了什么、数据库各状态表示什么，以及为什么本轮没有出现严重的资金或权益安全问题。

本次只扩写 Markdown 报告，不重新执行 JMeter，不连接数据库或外部服务，不删除保留数据，不修改 Java、SQL、Lua、Groovy、JMX 或运行证据。

## 证据分层

报告中的信息按照以下优先级组织：

1. **机器证据**：正式 Run 的 manifest、verdict、八段 CSV/JTL、最终 40,000 行时间戳证据、PostgreSQL 输出、Redis/RabbitMQ JSON。所有数量和时间统计必须来自这一层。
2. **源码合同**：数据库状态枚举、时间字段定义、Lua 状态迁移和最终 SQL 门禁，用于解释机器证据含义。
3. **历史对话背景**：此前关于 `paid_at >= expires_at`、PENDING/CLOSING 延迟、`hardCloseAt`、RabbitMQ 积压、消费者数量、回调来源和 `provider_trade_no` 的疑问及最终判断。对话只作问题背景和结论演进，不覆盖机器证据。

报告不得把口头估算写成正式统计。如果聊天中的旧漂移数字与保留证据重新计算结果不同，以保留证据为准，并明确区分“当时观察”与“最终复算”。

## 报告结构

### 1. 执行摘要与判定层级

报告同时给出三层判定：

- 正式 Runner 与最终 PostgreSQL 扫描结果。
- 微秒边界、订单终态、权益与退款安全结果。
- 已知验收口径偏差和测试器缺陷。

最终措辞固定为：本轮没有越过 `hardCloseAt` 仍错误发放、永久 PENDING/CLOSING、未决权益、未解析回调或队列积压等严重问题；但状态消息到达顺序存在毫秒级竞态，`closing_deadline_at` 的可观测结果不完全等同于墙上时钟阶段。

### 2. 系统链路与时间模型

用文字流程说明：

```text
JMeter 创建订单
→ 发起支付并写入 payment_started_at
→ RabbitMQ 延时检查推进 PENDING/CLOSING
→ 本机模拟回调写入 callback 事实
→ Redis Lua 原子裁决
→ PostgreSQL 批量持久化
→ 权益发放或退款侧终态
→ SQL/Redis/RabbitMQ 最终验收
```

解释 `expiresAt`、`hardCloseAt`、`closing_deadline_at`、`paid_at`、`received_at` 和 `resolved_at` 的角色，并强调最终安全裁决使用服务端 `received_at`，不是前端点击时间或计划调度时间。

### 3. 数据库状态字典

完整记录 `membership_order.status`：

| 数值 | 状态 | 是否终态 | 业务含义 |
| ---: | --- | --- | --- |
| 0 | PENDING_PAYMENT | 否 | 订单仍在正常支付期，或状态迁移消息尚未把它推进到 CLOSING |
| 1 | CLOSING | 否 | 订单已进入软关闭窗口，等待支付方查询或硬截止收敛 |
| 2 | PAID | 是 | 支付事实有效且权益裁决为 APPLIED |
| 3 | CANCELLED | 是 | 用户主动取消 |
| 4 | CLOSED | 是 | 超时或系统安全关闭；迟到成功回调走退款侧 |

同时解释：

- callback resolution：`APPLIED`、`ALREADY_APPLIED`、`REFUND_REQUIRED`、`REJECTED`。
- entitlement resolution：`APPLIED`、`NOT_GRANTED`、`REFUND_REQUIRED`、`LEGACY_NOT_GRANTED`。
- membership tier 数值：FREE=0、GO=1、EDU=2、TEAM=3、PLUS=4、PRO=5、MAX=6。

报告必须明确：本轮最终只出现订单状态 2 和 4；状态 0、1、3 的最终数量均为 0。`CANCELLED` 没有出现在本轮正式八区段，因为本套件不是取消竞态测试。

### 4. 八区段逐段审计

除总表外，每个区段单独说明：

- 账号范围、目标偏移和目标套餐分布。
- 实际 `received_at` 落在过期前、软关闭窗口或硬截止之后的数量。
- APPLIED/REFUND_REQUIRED 数量。
- PAID/CLOSED 最终状态数量。
- `closing_deadline_at` NULL/非空数量及原因。
- 调度漂移 Min/P50/P95/P99/Max。
- 该区段证明了什么，以及不能证明什么。

特别说明：

- E-A1 全部在软关闭窗口但 deadline 全空，是回调先于异步 PENDING→CLOSING 持久化的合法竞态。
- E-AR 同时出现 deadline 空和非空，证明存储状态迁移顺序不同，但安全裁决相同。
- H-P1/H-PR 按实际接收时间动态分裂，不应固定要求 5,000/0 或 0/5,000。
- H-A1/H-AR 全部退款，证明硬截止之后没有错误发放。

### 5. 历史对话问题演进

把此前聊天整理为主题化记录，不逐字复制冗长对话：

1. 为什么 `paid_at >= expires_at` 仍可能 PAID：因为软关闭窗口仍允许已在过期前发起的支付完成，真正硬边界是 `hardCloseAt`。
2. 为什么超过 `expiresAt` 仍显示 PENDING_PAYMENT：RabbitMQ 状态迁移是异步的，业务时间已进入软关闭不等于存储状态已立即改变。
3. 为什么 E-A1 没有 `closing_deadline_at`：回调先赢得竞态并直接支付，CLOSING 迁移无需再落库。
4. 为什么 `-1ms` 大量跨界：Windows/JVM/线程池/HTTP 调度漂移中位数远大于 1ms。
5. 是否连接真实第三方平台：没有，使用本机受控模拟回调。
6. 40 万压测为什么积压：RabbitMQ Channel、本机临时端口和消费者吞吐共同形成容量问题；因此正式边界验证缩容到 40,000 并使用每队列 48 个消费者。
7. `provider_trade_no` 为什么退款订单被回填：测试验收口径误判导致显式代码修改，不是原业务设计。

### 6. HTTP、重试与基础设施

详细列出 120,200 个逻辑请求的操作分布、HTTP 状态、第一次/第二次尝试数量，并解释有界重试使用相同幂等键，不把第二次尝试统计成第二笔订单。

Redis 部分记录 v1/v2 Key、四个工作集合及其最终数量。RabbitMQ 部分记录两条业务队列、消费者、prefetch、Ready、Unacked 和两条 DLQ。报告区分本轮会员队列与 RabbitMQ 中其他业务队列，避免把无关队列的 Ready 数量误判为会员支付积压。

### 7. SQL 门禁与证据字段字典

解释最终 SQL 的基数、时间、状态、权益和收敛门禁，并提供用于复核的只读 SQL 示例。附录列出以下 CSV 的用途和关键字段：

- `scenario-orders.csv`
- `callback-dispatch.csv`
- `request-results.csv`
- `settlement-wait.csv`
- `server-time-verdict.csv`
- `time-drift.csv`
- `final-timestamp-evidence.csv`

### 8. 两项测试过程问题

保留并扩写：

- 退款侧 `provider_trade_no`：原设计、错误验收假设、显式回填、当前数据表现、对边界结论的影响和本轮未认证范围。
- 小数微秒/BIGINT：产生原因、COPY 失败、NUMERIC+TRUNC 复核、40,000/40,000 一致、后续 `floorDiv` 修复及其不属于业务缺陷的原因。

### 9. 结论、风险与后续边界

报告不隐瞒以下限制：

- 运行环境是 Windows 本机，不代表生产 Linux 容量上限。
- 本轮验证时间边界安全，不等同于 40 万容量压测通过。
- 本轮使用本机模拟回调，不证明真实第三方平台网络和签名 SLA。
- 本轮正式代码包含退款订单流水回填，所以不能认证原字段设计。
- 测试完成后的 Groovy/SQL 验证器修正是在保留数据上复核，不是重新执行八区段。

## 自检标准

- 八段数量合计必须满足 `24,987 + 15,013 = 40,000`。
- 最终状态必须满足 PAID=24,987、CLOSED=15,013、其他状态=0。
- 过期前、软关闭、硬截止后三类合计必须为 40,000。
- 所有证据链接必须存在。
- 不得包含 Access Token、密码、密钥或完整签名材料。
- 不得把对话推测写成机器事实。
- 不得宣称存在 Exactly Once、真实第三方支付或生产容量结论。
