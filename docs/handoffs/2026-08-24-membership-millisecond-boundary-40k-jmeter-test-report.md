# 会员支付 40,000 账号微秒边界 JMeter 测试报告

测试日期：2026-08-23～2026-08-24。

测试项目：`C:\Users\damn\Desktop\ai-temperate-main`。

正式 Run ID：`membership-millisecond-boundary-20260823-224500`。

测试模式：`loadtest-realtime`，应用端口 `6655`，真实执行 `PENDING_PAYMENT 5 分钟 + CLOSING 5 分钟`。

源码指纹：`03d5a2d17d52f4d082b1eeb82218d67b9a409f62efe23a35b6ef5baf7d3631da`。

数据保留标记：`dataPreserved=true`。

> 本报告基于已经保留的 JMeter、PostgreSQL、Redis 与 RabbitMQ 正式运行证据重新生成。测试使用本机受控模拟支付回调，没有连接公网第三方支付测试平台，也没有调用真实退款平台。测试完成后保留全部 40,000 笔订单和回调，没有执行 `/reset` 或数据清理。

## 一、执行结论

本轮 40,000 账号、8×5,000 微秒边界测试的状态机、回调裁决、订单终态、权益结果、Redis 收敛和 RabbitMQ 收敛均通过正式验收。

核心结果如下：

| 验收项 | 结果 |
| --- | ---: |
| 固定测试账号 | 40,000 |
| 唯一订单 | 40,000 |
| 唯一回调 | 40,000 |
| `APPLIED` | 24,987 |
| `REFUND_REQUIRED` | 15,013 |
| PENDING_PAYMENT/CLOSING 未决订单 | 0 |
| 回调裁决不一致 | 0 |
| 权益裁决不一致 | 0 |
| Redis callback/dirty 待处理集合 | 0 |
| 两条会员 RabbitMQ 业务队列 Ready/Unacked | 0/0 |
| 两条会员 RabbitMQ DLQ | 0 |
| 正式 PostgreSQL 最终验证 | PASS |
| 正式 Runner verdict | PASS |
| 测试数据 | 已保留 |

最终准确结论为：

```text
微秒边界状态机、终态与权益安全 PASS；
测试过程中保留 1 项 provider_trade_no 验收口径误判，
以及 1 项 JMeter/SQL 微秒数值类型合同缺陷。
```

其中，退款订单 `provider_trade_no` 的原业务设计在正式运行后由业务方重新确认：退款侧流水号应保存在回调表，订单字段可以保持 `NULL`。本轮运行代码已经执行退款侧订单回填，因此本轮不能用于证明该字段原设计合同；这一限制不影响 40,000 笔订单的时间边界裁决、终态或权益安全结果。

## 二、测试环境与固定合同

### 2.1 运行环境

| 项目 | 正式配置 |
| --- | --- |
| 运行机器 | Windows 本机开发环境 |
| JMeter | 本机非 GUI 运行，Groovy 业务编排 |
| 应用实例 | 单一 `loadtest-realtime` 实例，端口 `6655` |
| PostgreSQL | 本机真实测试库 |
| Redis | 本机真实 Redis，会员支付 v2 Key |
| RabbitMQ | 本机 Docker RabbitMQ，Quorum Queue |
| 会员队列消费者 | 每条业务队列 48 个消费者 |
| 单消费者 prefetch | 20 |
| 创建并发 | 4,096 |
| HTTP 并发 | 4,096 |
| 正式预检 | 120 秒 |
| 区段间稳定窗口 | 60 秒 |
| 支付回调来源 | 本机受控模拟回调，不连接真实第三方平台 |

正式运行开始时间为 `2026-08-24T03:45:04.1905809Z`，完成时间为 `2026-08-24T05:11:14.8607819Z`。换算为本机夏令时约为 2026-08-23 22:45 至 2026-08-24 00:11。

### 2.2 账号与套餐分布

固定账号范围：

```text
70000000000000000 ～ 70000000000039999
```

八个区段各 5,000 个账号，每段套餐分布完全一致：

| 套餐 | 每段 | 八段合计 |
| --- | ---: | ---: |
| GO | 1,250 | 10,000 |
| PLUS | 1,250 | 10,000 |
| PRO | 1,250 | 10,000 |
| MAX | 1,250 | 10,000 |

每段另执行 25 个 TEAM 负向探针，八段共 200 个。TEAM 探针全部得到预期 HTTP 400，且没有创建 TEAM 订单。

### 2.3 业务时间规则

固定业务边界为：

```text
hardCloseAt = expiresAt + 5 minutes
```

支付允许与退款裁决不是简单判断 `paid_at < expires_at`，而是同时遵守：

```text
payment_started_at < expires_at
paid_at >= payment_started_at
paid_at <= received_at
received_at < hardCloseAt  → APPLIED
received_at >= hardCloseAt → REFUND_REQUIRED
```

因此，回调在 `expiresAt` 之后到达并不必然退款。只要支付在过期前合法发起，且后台在 `hardCloseAt` 前收到有效付款通知，订单仍可从 PENDING_PAYMENT 或 CLOSING 收敛为 PAID。

`closing_deadline_at=NULL` 也不必然表示异常：订单可能已经进入软关闭业务时间窗，但 RabbitMQ 状态迁移消息尚未执行，回调先完成了支付。此时订单可以从存储状态 PENDING_PAYMENT 直接收敛为 PAID，未必需要先落一次 CLOSING。

## 三、八个区段设计

| 顺序 | 区段 | 用户序号 | 参照边界 | 调度目标 | 数量 |
| ---: | --- | --- | --- | --- | ---: |
| 1 | E-P1 | 00000～04999 | `expiresAt` | 全部 `-1ms` | 5,000 |
| 2 | E-PR | 05000～09999 | `expiresAt` | `-1000ms～-2ms`，500 个点循环 | 5,000 |
| 3 | E-A1 | 10000～14999 | `expiresAt` | 全部 `+1ms` | 5,000 |
| 4 | E-AR | 15000～19999 | `expiresAt` | `0ms～+998ms`，500 个点循环 | 5,000 |
| 5 | H-P1 | 20000～24999 | `hardCloseAt` | 全部 `-1ms` | 5,000 |
| 6 | H-PR | 25000～29999 | `hardCloseAt` | `-1000ms～-2ms`，500 个点循环 | 5,000 |
| 7 | H-A1 | 30000～34999 | `hardCloseAt` | 全部 `+1ms` | 5,000 |
| 8 | H-AR | 35000～39999 | `hardCloseAt` | `0ms～+998ms`，500 个点循环 | 5,000 |

这些偏移是调度目标，不是对操作系统、JVM、HTTP 和线程池的零漂移保证。最终裁决必须使用服务端实际 `received_at` 微秒值，不能只使用计划区段名称。

## 四、JMeter 与 HTTP 执行结果

每段的外层 JTL 包含一个 `Execute Real Millisecond Boundary Wave` 采样器，八个外层采样器全部 `success=true`。实际 HTTP 操作明细写入每段 `request-results.csv`，结果如下：

| 操作 | 每段 | 八段合计 | HTTP 结果 |
| --- | ---: | ---: | --- |
| TEAM 负向探针 | 25 | 200 | 400，符合预期 |
| 创建订单 | 5,000 | 40,000 | 201 |
| 发起支付 | 5,000 | 40,000 | 201 |
| 模拟支付回调 | 5,000 | 40,000 | 200 |
| **逻辑请求合计** | **15,025** | **120,200** | **0 个逻辑失败** |

本机短暂连接异常使用原幂等键执行有界重试。120,200 个逻辑请求中，118,821 个一次完成，1,379 个第二次完成，没有请求使用第三次尝试，也没有因重试产生重复订单或重复回调。

## 五、八区段最终结果

### 5.1 裁决、终态与实际接收区间

| 区段 | APPLIED | REFUND_REQUIRED | PAID | CLOSED | `closing_deadline_at` NULL/非空 | `received_at < expiresAt` | 软关闭区间 | `received_at >= hardCloseAt` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| E-P1 | 5,000 | 0 | 5,000 | 0 | 5,000 / 0 | 24 | 4,976 | 0 |
| E-PR | 5,000 | 0 | 5,000 | 0 | 4,998 / 2 | 4,889 | 111 | 0 |
| E-A1 | 5,000 | 0 | 5,000 | 0 | 5,000 / 0 | 0 | 5,000 | 0 |
| E-AR | 5,000 | 0 | 5,000 | 0 | 2,050 / 2,950 | 0 | 5,000 | 0 |
| H-P1 | 25 | 4,975 | 25 | 4,975 | 0 / 5,000 | 0 | 25 | 4,975 |
| H-PR | 4,962 | 38 | 4,962 | 38 | 0 / 5,000 | 0 | 4,962 | 38 |
| H-A1 | 0 | 5,000 | 0 | 5,000 | 0 / 5,000 | 0 | 0 | 5,000 |
| H-AR | 0 | 5,000 | 0 | 5,000 | 0 / 5,000 | 0 | 0 | 5,000 |
| **合计** | **24,987** | **15,013** | **24,987** | **15,013** | **17,048 / 22,952** | **4,913** | **20,074** | **15,013** |

说明：

- E-P1 虽然目标是 `expiresAt - 1ms`，但只有 24 笔实际在过期前收到，4,976 笔因调度与传输漂移进入软关闭区间。它们仍在 `hardCloseAt` 前收到，因此全部正确 APPLIED。
- E-PR 有 111 笔实际跨过 `expiresAt`，但没有跨过 `hardCloseAt`，所以仍全部 APPLIED。
- E-A1 和 E-AR 全部位于软关闭窗口，全部 APPLIED。E-A1 的 `closing_deadline_at` 全为空，是“回调先于异步 CLOSING 状态迁移完成”的合法结果，不是永久卡在 PENDING_PAYMENT。
- H-P1 的目标只有硬截止前 1ms，在本机实际漂移下 4,975 笔跨过硬截止，因此退款；25 笔仍在硬截止前收到，因此发放。
- H-PR 的 5,000 个计划点中有 38 笔实际跨过硬截止，动态裁决为退款，其余 4,962 笔正确发放。
- H-A1 与 H-AR 全部在硬截止点或之后收到，因此全部 REFUND_REQUIRED，并保持 FREE。

### 5.2 调度漂移分布

下表的 `dispatch_drift_micros` 定义为 `dispatch_started_at - target_at`。本轮原始 Groovy 证据曾输出小数微秒，本表按六位微秒时间戳可表达的整数精度向零截断后统计。

| 区段 | 最小值 | P50 | P95 | P99 | 最大值 |
| --- | ---: | ---: | ---: | ---: | ---: |
| E-P1 | 27µs | 8,051µs | 14,729µs | 15,752µs | 19,853µs |
| E-PR | 21µs | 8,318µs | 14,996µs | 16,264µs | 22,773µs |
| E-A1 | 9µs | 7,930µs | 14,844µs | 15,852µs | 29,167µs |
| E-AR | 1µs | 8,204µs | 14,914µs | 15,756µs | 17,941µs |
| H-P1 | 14µs | 7,760µs | 14,671µs | 15,580µs | 19,392µs |
| H-PR | 42µs | 7,973µs | 14,849µs | 15,716µs | 18,278µs |
| H-A1 | 17µs | 7,836µs | 15,524µs | 24,848µs | 50,008µs |
| H-AR | 17µs | 8,184µs | 14,982µs | 16,310µs | 25,542µs |

该分布解释了为什么 `-1ms` 目标不可能保证全部落在边界前：P50 漂移约 8ms，远大于 1ms。测试器的职责是记录真实漂移并按服务端 `received_at` 裁决，而不是把实际时间伪造成计划时间。

## 六、PostgreSQL 与微秒时间验收

### 6.1 时间字段精度

以下十个业务时间字段采用 `TIMESTAMPTZ(6)`：

```text
membership_order
  payment_started_at
  expires_at
  closing_deadline_at
  paid_at
  entitlement_resolved_at
  created_at
  updated_at

membership_payment_callback
  paid_at
  received_at
  resolved_at
```

最终证据统一输出 UTC 六位小数，例如：

```text
2026-08-24T03:52:18.634194Z
```

微秒精度表示端到端保存和比较到六位小数，不要求每一个来源时间的最后三位必须非零，也不伪造上游未提供的精度。

### 6.2 最终 SQL 检查范围

正式 PostgreSQL 验证执行了以下门禁：

- 场景清单必须为 40,000 个唯一用户和 40,000 个唯一订单。
- 每个固定用户有且只有一笔本轮订单。
- 每个订单有且只有一笔回调。
- `expires_at` 必须与计划值一致。
- 非空 `closing_deadline_at` 必须严格等于计划 `hardCloseAt`。
- 实际 `received_at` 微秒比较必须与 `APPLIED/REFUND_REQUIRED` 一致。
- APPLIED 订单必须为 PAID，并获得目标 GO/PLUS/PRO/MAX 套餐。
- REFUND_REQUIRED 订单必须为 CLOSED，并保持 FREE。
- callback resolution、entitlement resolution 和 resolved timestamp 均不得为空。
- 不允许留下 PENDING_PAYMENT、CLOSING 或未决权益。

最终 SQL 输出：

```text
COPY 40000
COPY 40000
COPY 40000
verdict = PASS
```

## 七、Redis 与 RabbitMQ 收敛

### 7.1 Redis

最终区段完成后：

| 项目 | 数量 |
| --- | ---: |
| v1 会员支付 Key | 0 |
| v2 会员支付 Key | 40,000 |
| callback ready | 0 |
| callback processing | 0 |
| order dirty | 0 |
| order dirty processing | 0 |

这表示 40,000 个正式运行事实保留，但所有回调领取与订单持久化工作集合已经清空，没有待处理或处理中任务。

### 7.2 RabbitMQ

每个区段完成后，两条会员业务队列均满足：

| 队列 | 类型 | 消费者 | Ready | Unacked |
| --- | --- | ---: | ---: | ---: |
| `membership.payment.check.queue` | quorum | 48 | 0 | 0 |
| `membership.closing.check.queue` | quorum | 48 | 0 | 0 |

对应 DLQ：

```text
membership.payment.check.dlq = 0
membership.closing.check.dlq = 0
```

因此最终不存在会员到期检查、硬关闭检查或死信积压。

## 八、测试过程中保留的两项问题

### 8.1 退款侧 `provider_trade_no` 验收口径误判

#### 原业务设计

业务方在正式运行后再次确认：

```text
APPLIED：
  membership_order.provider_trade_no 可以填写第三方流水号。

REFUND_REQUIRED：
  membership_payment_callback.provider_trade_no 保存迟到付款事实；
  membership_order.provider_trade_no 可以保持 NULL；
  退款处理从回调事实取得第三方流水号。
```

订单未进入正常支付成功状态时，不要求把迟到付款的第三方流水同步写入订单。订单与回调不是该字段的全状态镜像。

#### 测试期间发生的误判

早期最终验证器错误地对 APPLIED 和 REFUND_REQUIRED 使用同一合同，要求：

```text
order.provider_trade_no
= callback.provider_trade_no
= JMeter dispatch provider_trade_no
```

退款订单按原设计保持 `NULL` 时，SQL 因此错误判定失败。随后测试期间又为了满足这个错误断言，在退款权益结算中增加了订单流水号回填。

因此，本轮 15,013 个 REFUND_REQUIRED 订单的 `membership_order.provider_trade_no` 均有值，不是原状态机自然产生的结果，而是测试期间显式增加的回填逻辑造成的。

#### 准确分类与影响

| 项目 | 判定 |
| --- | --- |
| 原退款裁决 | 正确 |
| 原回调表保存第三方流水 | 正确 |
| 原订单退款侧流水为空 | 符合业务设计，不是产品缺陷 |
| 最终 SQL 强制订单流水非空 | 验收口径错误 |
| 为通过断言而增加退款订单回填 | 与已确认业务设计不一致 |
| 对时间边界、订单终态和权益结果的影响 | 无直接影响 |

报告保留这一项，是为了说明测试过程和当前数据为什么与原字段设计不同。正式 Run 可以证明 15,013 个退款裁决均安全保持 FREE，但不能证明“退款订单流水号保持 NULL”这一字段合同。

### 8.2 小数微秒被最终验证器误按 `BIGINT` 读取

#### 现象

本轮 JMeter Groovy 最初使用：

```groovy
Duration.between(targetAt, startedAt).toNanos() / 1_000L
```

Groovy 的 `/` 返回 `BigDecimal`，因此原始证据中可能出现：

```text
13363.4
```

其含义是 `13,363.4µs`，不是 PostgreSQL 时间戳丢失精度。但初版最终验证器把 `dispatch_drift_micros` 定义成 `BIGINT`，导致 COPY 无法接收小数。

#### 修复与复核

本轮保留数据的最终验证临时表改用 `NUMERIC(30,9)` 接收原始值，再使用 `TRUNC(... )::BIGINT` 与六位微秒时间戳重算结果比较。复核结果：

```text
向零截断后与六位微秒时间戳重算一致：40,000 / 40,000
使用四舍五入会产生差异：20,045 / 40,000
```

后续 JMeter 生成逻辑已经改为：

```groovy
Math.floorDiv(
    Duration.between(targetAt, startedAt).toNanos(),
    1_000L
)
```

以后直接输出整数微秒，正式 SQL 模板继续使用 `BIGINT`。

#### 准确分类与影响

这是 JMeter Groovy 与 PostgreSQL 最终验证器之间的数据类型合同缺陷，不是 JUnit 问题，也不是会员支付业务逻辑、Java 时间判断或 PostgreSQL `TIMESTAMPTZ(6)` 精度缺陷。八段业务请求、订单、回调和时间戳均不需要伪造或修改；最终 SQL 已在保留的 40,000 行证据上 PASS。

## 九、结论边界

本报告作出以下结论：

- 40,000 个固定用户均完成一笔真实订单和一笔本机 HTTP 模拟支付回调。
- 八个区段均按实际 `received_at` 微秒时间正确落在 APPLIED 或 REFUND_REQUIRED 一侧。
- `received_at == hardCloseAt` 属于退款侧；硬截止前收到的合法付款属于发放侧。
- 24,987 个 APPLIED 用户获得目标套餐，15,013 个 REFUND_REQUIRED 用户保持 FREE。
- 没有订单永久停留在 PENDING_PAYMENT 或 CLOSING。
- Redis 与两条 RabbitMQ 会员业务队列全部收敛，DLQ 为 0。
- 正式运行数据已经保留，没有执行成功后清理。

本报告不作以下宣称：

- 不宣称连接了真实第三方支付平台。
- 不宣称毫秒目标可以消除 Windows、JVM、线程调度和 HTTP 漂移。
- 不宣称 PostgreSQL、Redis 与 RabbitMQ 之间具备分布式事务或 Exactly Once。
- 不把退款订单原本不回填 `provider_trade_no` 描述为产品缺陷。
- 不使用本轮结果证明退款订单 `provider_trade_no` 应当非空。
- 不把最终验证器的小数/BIGINT 问题描述成业务状态机问题。

## 十、证据索引

正式证据根目录：

- [正式运行目录](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/)
- [运行清单](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/run-manifest.json)
- [最终 verdict](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/verdict.json)
- [数据保留证明](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/data-preserved.json)
- [最终 PostgreSQL 验证输出](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/final-postgres-verification.txt)
- [40,000 行最终微秒证据](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/final-timestamp-evidence.csv)
- [40,000 行场景清单](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/scenario-orders-all.csv)
- [40,000 行回调调度证据](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/callback-dispatch-all.csv)

八个区段：

- [E-P1](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/E-P1/)
- [E-PR](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/E-PR/)
- [E-A1](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/E-A1/)
- [E-AR](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/E-AR/)
- [H-P1](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/H-P1/)
- [H-PR](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/H-PR/)
- [H-A1](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/H-A1/)
- [H-AR](../../loadtest-output/soak/membership-millisecond-boundary-20260823-224500/millisecond-boundary/H-AR/)

`loadtest-output` 是本机测试产物目录，可能不进入 Git。以上链接用于当前工作区审计，不能假设其他克隆环境自动包含这些证据。

## 十一、最终判定

```text
正式 Runner：PASS
PostgreSQL 最终扫描：PASS
Redis/RabbitMQ 最终收敛：PASS
微秒边界状态机与权益安全：PASS
退款订单 provider_trade_no 原设计字段合同：本轮未认证，存在测试口径偏差
测试数据：PRESERVED
```

在明确披露上述两项测试过程问题后，本轮 40,000 账号 JMeter 测试可以作为会员支付毫秒调度、微秒事实保存、软关闭支付和硬截止退款逻辑的正式通过证据。
