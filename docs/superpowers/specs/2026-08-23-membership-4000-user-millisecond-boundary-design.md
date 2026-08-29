# 会员支付 4,000 用户毫秒边界极限测试设计

## 1. 目标与范围

本测试使用 4,000 个独立测试用户和 4,000 个短期 Access Token，在真实 `PENDING_PAYMENT = 5 分钟`、`CLOSING = 5 分钟` 的配置下验证两个关键时间边界：

- `expiresAt`：PENDING_PAYMENT 进入 CLOSING 的状态边界；它不是最终付款拒绝边界。
- `hardCloseAt = expiresAt + 5 分钟`：支付事实由 APPLIED 切换为 REFUND_REQUIRED 的最终业务边界。

测试只验证现有业务语义，不修改订单、回调、BAR/本地 Provider 或权益裁决逻辑。八个 500 用户组的正式验收全部由 JMeter 发起真实 HTTP 链路；所有付款事实必须通过本地模拟支付的正式 HTTP 回调入口进入系统，并真实经过 Redis、RabbitMQ、Callback Worker 和 PostgreSQL。禁止用 Java 单元测试、直接 SQL、直接 Redis 写入或伪造终态替代任何一笔正式边界订单。

Java/JUnit 和 PowerShell 静态测试只验证测试夹具、分组、调度器和断言工具自身的安全性与确定性，不计入 4,000 笔业务结果，也不能作为边界行为 PASS 的证据。

## 2. 固定测试用户

测试用户 ID 固定为闭区间：

```text
70000000000000000 ～ 70000000000003999
```

共 4,000 个 ID。这些记录是可重复使用的固定测试模板，不在测试结束后删除。首次准备时，只有当 `userloginidentity`、`user_profile` 和 `user_membership_quota` 三张表在该区间均为空时才允许批量创建；后续准备时必须逐批校验 4,000 条身份、资料和额度记录与确定性模板完全一致。区间内只要出现数量不完整、邮箱不匹配、资料缺失或其他非模板数据，测试必须停止，禁止覆盖。

夹具只允许创建这个固定区间：

- 邮箱使用确定性的 `.invalid` 地址并保证大小写不敏感唯一。
- 手机号、密码、OAuth Subject 和 TOTP 数据保持为空或安全默认值。
- `user_profile.account_status = ACTIVE`。
- `user_membership_quota.membership_tier = FREE`，额度为 FREE 完整额度，七天周期未激活，会员到期时间为空。
- 不创建真实 Refresh Session，不走真实登录，不生成长期凭据。

每轮开始前把全部 4,000 个模板用户重置为 FREE 完整额度、七天周期未激活且会员到期时间为空；每轮结束后只精确删除本轮拥有的订单与回调，重新执行同样的 FREE 重置，并清除对应 Redis 支付/资料缓存及本轮 RabbitMQ 残留。身份、资料和额度模板必须保留，禁止范围外修改或删除。

## 3. 八个测试组

每组 500 个用户都从 FREE 发起一次无升级、无抵扣的个人套餐整月购买，并严格平均分配为 125 笔 GO、125 笔 PLUS、125 笔 PRO、125 笔 MAX。全部订单在有效期内先完成 Payment Attempt，以确保 Provider 交易绑定存在。

每个 1,000 用户子波次另外选择 5 个随后仍会执行合法个人套餐购买的用户，先发起 TEAM 负向请求，共 20 次。TEAM 请求必须被业务规则拒绝，不得创建 `membership_order`、不得调用 Provider，也不得改变用户额度；随后该用户仍按原定个人套餐参与本组合法订单。因此总合法订单数仍严格为 4,000。

| 组 | 用户后缀 | 计划 HTTP 回调时刻 | 数量 | 业务重点 |
| --- | --- | --- | ---: | --- |
| E-P1 | 0000～0499 | `expiresAt - 1ms` | 500 | PENDING 边界前同一点并发 |
| E-PR | 0500～0999 | `expiresAt - 1000ms, -998ms, …, -2ms` | 500 | PENDING 边界前最后一秒分布 |
| E-A1 | 1000～1499 | `expiresAt + 1ms` | 500 | CLOSING 边界后同一点并发 |
| E-AR | 1500～1999 | `expiresAt + 0ms, +2ms, …, +998ms` | 500 | 包含精确 expiresAt 的后一秒分布 |
| H-P1 | 2000～2499 | `hardCloseAt - 1ms` | 500 | 最终硬截止前同一点并发 |
| H-PR | 2500～2999 | `hardCloseAt - 1000ms, -998ms, …, -2ms` | 500 | 硬截止前最后一秒分布 |
| H-A1 | 3000～3499 | `hardCloseAt + 1ms` | 500 | 硬截止后同一点并发 |
| H-AR | 3500～3999 | `hardCloseAt + 0ms, +2ms, …, +998ms` | 500 | 包含精确 hardCloseAt 的后一秒分布 |

`0,+2,…,+998` 恰好包含 500 个点，并显式包含边界本身。所有期望结果均按服务端实际 `receivedAt` 分桶，不按客户端计划发送时间裁决。

## 4. 执行拓扑

测试拆成四个连续子波次，每个子波次 1,000 个用户：

1. `E-PRE`：E-P1 + E-PR。
2. `E-AFTER`：E-A1 + E-AR。
3. `H-PRE`：H-P1 + H-PR。
4. `H-AFTER`：H-A1 + H-AR。

拆分只用于隔离证据和控制本地单实例负载，不减少 4,000 个独立订单，也不改变每组 500 个请求的并发强度。四个子波次之间只等待前一波次完成终态核验和资源回落，不加入人为长观察窗。

每笔订单独立以服务端返回的 `expiresAt` 计算目标时刻，不能假设 1,000 笔订单拥有相同截止时间。Runner 必须记录订单创建时间的分布和 `expiresAt` 跨度。

## 5. 精确调度

禁止为 4,000 个用户创建 4,000 个长期休眠的 JMeter 线程。使用固定大小的异步调度池：

1. 以有界并发创建订单并调用 Payment Attempt。
2. 根据每笔订单自己的 `expiresAt`/`hardCloseAt` 计算绝对目标毫秒。
3. 使用 `ScheduledThreadPoolExecutor` 或等价的单调时钟调度结构登记回调任务。
4. 到点后由有界 HTTP 工作池发送请求；同一点 500 请求允许产生真实排队和漂移。
5. 调度器不得通过修改服务端时间、数据库时间或订单截止字段追求“命中率”。

客户端不宣称能够保证 `-1ms` 请求一定在截止前到达。`-1ms` 组故意让实际 `receivedAt` 跨越边界，用于验证服务端在真实调度漂移下仍逐笔作出正确裁决。

## 6. 受控夹具与 Token 安全边界

现有 16 用户白名单继续保持不变，不能扩大为 4,000 条配置。新增独立的毫秒边界夹具能力：

- 只在 `loadtest-realtime` 且显式边界测试开关开启时注册。
- 只接受回环来源。
- 固定用户区间、固定总数、固定分组和确定性套餐映射，接口不接收任意用户 ID、邮箱、套餐或数量。
- Token 按 500 条一页签发，页码只能为 `0..7`；单个响应禁止返回其他用户 Token。
- Access Service 只在边界开关开启时额外接受该固定区间；原 16 用户白名单校验不变。
- Token 文件写入 Git 忽略目录，不进入 JTL、HTML、命令参数、日志或最终报告。
- 测试完成后精确删除 8 个 Token 分片文件。

夹具创建、模板校验和 FREE 重置均使用批量 Mapper，每批 500 条，不允许逐用户数据库 I/O。测试数据清理只按本轮已记录的精确订单集合删除回调和订单；禁止删除身份、资料或额度模板。

## 7. 逐笔裁决规则

所有订单必须记录：

```text
userId
orderId（报告仅使用脱敏摘要）
group
targetAt
clientDispatchAt
clientCompletedAt
serverReceivedAt
expiresAt
hardCloseAt
providerPaidAt
finalStatus
entitlementResolution
callbackResolution
dispatchDriftMs
receivedDriftMs
```

逐笔业务断言：

- `serverReceivedAt < hardCloseAt`：订单最终必须 PAID，订单权益 APPLIED，用户最终等级必须等于该笔订单的确定性目标套餐。
- `serverReceivedAt >= hardCloseAt`：订单不得转为 PAID，权益必须 REFUND_REQUIRED，用户保持 FREE。
- `expiresAt` 前后但仍早于 `hardCloseAt` 的请求均属于可接受支付事实，必须 APPLIED。
- `serverReceivedAt == hardCloseAt` 属于 REFUND_REQUIRED。
- 不允许权益重复发放、回调重复记录、同用户多笔活动订单或永久 PENDING/CLOSING。

测试必须分别报告“业务裁决正确性”和“调度/容量表现”。只要逐笔裁决错误，业务结果 FAIL；大量漂移但裁决全部正确时，业务正确性可 PASS，但容量报告必须如实标记 SLO 是否达标。

## 8. 容量与基础设施门禁

每个子波次前后检查：

- 仅 6655 监听，8080 未监听。
- PENDING 和 CLOSING 业务队列各恰好一个消费者。
- RabbitMQ Ready/Unacked/DLQ、Redis callback ready/processing、dirty/processing 记录前后基线。
- PostgreSQL 活动订单、未决权益、连接池和慢事务无异常。
- 应用源码指纹、Profile、真实 5+5 配置保持不变。

自动停止新增请求的条件：

- 发现区间外用户被修改。
- 同一用户出现两笔活动订单。
- 6655 之外的应用实例参与会员队列消费。
- 会员队列消费者数量不是 1。
- 5xx 持续超阈值、连接池耗尽、Redis/RabbitMQ 明显失控或意外 DLQ。
- Token、签名、密码或完整敏感字段进入产物。

停止后保留所有证据，不把失败波次与修复后波次拼接为正式 PASS。

## 9. 验证与清理

每个子波次完成后执行 SQL、Redis 和 RabbitMQ 三方核验。第四个子波次完成后总扫描 4,000 笔：

- 固定模板中存在并校验 4,000 个用户，本轮创建 4,000 个合法订单和 4,000 个 Payment Attempt。
- 8 个组各 500 条，无缺失、无重复。
- 每组 GO、PLUS、PRO、MAX 各 125 条；20 个 TEAM 负向请求全部拒绝且没有产生额外订单。
- 每笔结果与服务端 `receivedAt` 所属区间一致。
- APPLIED 用户等级和额度与订单目标套餐一致；REFUND_REQUIRED 用户保持 FREE。
- 终态后无活动订单、未决权益、Marker、ready/processing、dirty/processing 或意外 DLQ。

总报告完成并保存后，只删除本轮 4,000 笔订单及其回调、删除 8 个短期 Token 文件并重置模板额度。清理完成必须证明：本轮订单/回调为 0，固定区间的身份/资料/额度仍各为 4,000 且全部恢复 FREE 基线，Redis 本轮支付与资料缓存残留为 0，RabbitMQ 回到前置基线。

## 10. 与正式浸泡测试的关系

本测试是独立的极限边界波次，不替代正式 W01～W08。测试夹具和 Runner 代码冻结后，正式 W01～W08 必须在同一源码指纹下完整重跑，才能形成可合并的本地正式结论；随后才进入 BAR W09～W16。
