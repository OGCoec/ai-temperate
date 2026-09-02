# 会员支付 wxpay 502 与提前关单修复计划

## 目标

修复同一事故链上的两个不同缺陷：

1. Liuhao wxpay 创建请求返回 502 或结果不确定时，保留真实 Provider 事实，避免把“已创建但支付入口不可用”误判为“没有创建”，并避免重复创建。
2. 人工取消已发起的外部支付订单时，允许立即进入 `CLOSING`，但 `closing_deadline_at` 必须锚定原始 `expires_at + closingDuration`，禁止提前 `CLOSED`。

本计划只描述实施方案；实施前需要确认六号 wxpay 的真实响应契约和是否允许订单在原始到期前进入 `CLOSING`。

## 架构决策

- 502 是支付创建链路的触发信号，不直接负责订单状态迁移；只有经过状态校验的真实 `cancel` 请求才能进入人工 `CLOSING`。
- 将“Provider 已创建但本地无法展示支付入口”与“Provider 调用结果未知”分开建模；两者都必须保留交易号证据。
- 人工关单可以立即发送 Provider close 请求，但硬截止时间统一由订单原始 `expires_at` 推导。
- 业务层和 Redis Lua 都执行截止时间保护；Lua 是并发场景下的最终裁决，不信任调用方传入的 deadline。
- 不通过手动删除订单修复历史数据；历史 `CLOSED` 订单必须先查询 Provider 事实再决定对账、退款或人工告警。

## 实施任务

### 阶段 0：证据冻结与契约确认

**描述：** 固化当前工作树、日志和订单时间字段，确认六号 wxpay 的响应字段及支付入口类型。

**验收标准：**

- [ ] 形成一份脱敏的 wxpay 响应字段清单：HTTP 状态、业务码、签名、金额、`trade_no`、`pay_type`、`pay_info`。
- [ ] 明确 `jump`、`qrcode` 及未知 `pay_type` 的支持策略。
- [ ] 明确 `LIUHAO_CREATE_REJECTED`、`LIUHAO_CHECKOUT_UNAVAILABLE`、`LIUHAO_CREATE_OUTCOME_UNKNOWN` 的判定条件。
- [ ] 后续排查不再直接删除数据库或 Redis 订单记录。

**依赖：** 无。

**可能涉及文件：** 交接文档、Provider 契约说明、运行手册。

### 阶段 1：统一 Liuhao 创建响应观测

**描述：** 在 Liuhao REST 客户端和业务编排之间建立不可变的创建观测结果，使校验失败后仍保留 Provider 已返回的交易事实。

**验收标准：**

- [ ] 新增 `liuhao_create_payload_validation` 结构化日志，包含 `traceId`、订单脱敏标识、HTTP 状态类别、业务码类别、`trade_no_present`、`pay_type_class`、`pay_info_kind`、`type_match`、`amount_match`、`outcome` 和 `reason`。
- [ ] `trade_no_present` 从已解析的 Provider 观测结果读取；异常导致没有完整 `PaymentCreateResult` 时，不得把未知值伪造成 `false`，应记录观测是否可用。
- [ ] 日志不包含完整支付地址、签名原值、Token、完整交易号或响应正文。
- [ ] 对 HTTP 失败、超时、签名失败、业务拒绝、支付类型不支持和金额不匹配分别产生稳定 reason。

**依赖：** 阶段 0。

**可能涉及文件：** `LiuhaoPaymentRestClientImpl`、创建结果/异常类型、支付生命周期诊断类及对应测试。

### 阶段 2：防止已创建订单被重复创建

**描述：** 将 Provider 已创建但本地无法展示入口的场景与真正未创建场景分开，确保重试和前端提示不制造第二笔支付。

**验收标准：**

- [ ] `2xx + code=success + trade_no + 不支持的 pay_type` 保留交易号，并标记为 checkout 不可用，不得当作未创建。
- [ ] 超时或无响应标记为结果未知，进入现有查询/关单收敛路径。
- [ ] 明确拒绝且能证明 Provider 未创建时，才允许按未创建处理。
- [ ] 同一订单重试不会再次调用 Provider create；幂等键和已有交易号优先裁决。
- [ ] 前端收到 `LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE` 时，只有用户确认后才调用 `/cancel`，并能展示“旧入口不可恢复/正在关闭”，不会自动循环创建。

**依赖：** 阶段 1。

**可能涉及文件：** Liuhao 创建编排 Service、支付尝试持久化模型、Controller 响应 DTO、前端 `membership-plans.vue` 与支付 API 客户端。

### 检查点 A：创建链路

- [ ] wxpay 成功返回可用入口时仍能正常跳转。
- [ ] wxpay 返回交易号但入口类型不支持时，日志能显示 `trade_no_present=true`，且不会重复创建。
- [ ] alipay 现有成功流程不受影响。
- [ ] 502、超时和业务拒绝的外部错误码与内部 reason 一致。

### 阶段 3：修复人工 CLOSING 的时间锚点

**描述：** 修改人工取消路径，让截止时间从原始订单过期时间计算，而不是从取消请求时间计算。

**验收标准：**

- [ ] `startManualClosing()` 使用 `snapshot.expiresAt().plus(properties.closingDuration())`。
- [ ] 保留立即进入 `CLOSING` 和立即发送 Provider close 的现有行为，除非产品明确要求到期前不得进入 `CLOSING`。
- [ ] 订单创建 15 秒后人工取消时，`closingDeadlineAt` 仍等于原始 `expiresAt + closingDuration`。
- [ ] wxpay 与 alipay 不参与 deadline 计算，使用同一订单时间规则。

**依赖：** 阶段 0。

**可能涉及文件：** `MembershipOrderServiceImpl`、订单快照/状态迁移测试。

### 阶段 4：增加状态机和 Lua 防御性不变量

**描述：** 防止任何并发入口或旧消息使用错误 deadline 提前写入 `CLOSED`。

**验收标准：**

- [ ] `start_closing.lua` 拒绝早于 `expires_at + closingDuration` 的 deadline。
- [ ] `finalize_closing.lua` 在写 `CLOSED` 前再次验证硬边界；错误或缺失 deadline 只能返回受控结果并重新调度，不能直接关闭。
- [ ] 关单消费者在边界前只执行有限重试；到边界后才执行最终查询和终态裁决。
- [ ] callback marker、Provider 已支付和重复消息的并发保护保持有效。
- [ ] `TOO_EARLY`、`INVALID_DEADLINE`、基础设施失败均有结构化日志和指标。

**依赖：** 阶段 3。

**可能涉及文件：** `start_closing.lua`、`finalize_closing.lua`、`RedisMembershipOrderSnapshotStore`、`MembershipClosingCheckConsumerServiceImpl`。

### 阶段 5：人工关单审计与历史数据修复

**描述：** 补齐“谁触发了 cancel”证据，并安全处理已经存在的错误 deadline 或提前 CLOSED 订单。

**验收标准：**

- [ ] cancel 审计包含脱敏订单标识、provider、原状态、`paymentStartedAt` 是否存在、触发原因和 `traceId`。
- [ ] 能通过日志确认 502 后是否真的调用了 `/cancel`；支付尝试异常本身不能隐式调用 cancel。
- [ ] `CLOSING` 且 deadline 早于正确边界的订单，通过受控修复流程重新设置 deadline 并补发边界调度。
- [ ] 已经 `CLOSED` 的订单先查询 Provider，再决定对账、退款或人工告警；不直接批量改回状态。

**依赖：** 阶段 4。

**可能涉及文件：** cancel 审计日志、运维修复脚本/Service、数据核对 SQL、告警配置。

### 阶段 6：回归验证与灰度发布

**描述：** 用单元、Lua/Redis 集成和端到端场景验证两个缺陷及其联动链路，再分阶段发布。

**验收标准：**

- [ ] 覆盖 HTTP 2xx/4xx/5xx、超时、签名失败、金额不匹配、`jump`、`qrcode`、未知入口类型。
- [ ] 覆盖“502 → replay unavailable → 用户确认 cancel → CLOSING → 正确 deadline → CLOSED”。
- [ ] 覆盖早期消息、重复 cancel、重复 close、callback 并发和 Provider 已支付。
- [ ] 发布后监控 502 分类、trade number 丢失率、重复 create、`TOO_EARLY`、提前 CLOSED、关单延迟和死信数量。
- [ ] 灰度期间确认 alipay 和正常 wxpay 路径无回归，再扩大流量。

**依赖：** 阶段 1 至阶段 5。

## 风险与处理

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| 六号 wxpay 返回 `trade_no` 但支付入口类型未支持 | 可能重复创建或无法支付 | 保留交易号，标记 checkout 不可用，禁止重放并允许查询/关单 |
| 配置中的 closingDuration 后续发生变化 | 新旧订单边界不一致 | 评估是否需要持久化订单级窗口版本，禁止用新配置缩短旧订单窗口 |
| 历史订单已被手动删除 | 因果证据缺失 | 依赖脱敏日志和网关记录；无法证明的订单进入人工对账 |
| callback 与最终关单并发 | 可能丢失已支付事实 | 保留 callback marker 和 Lua CAS，终态前必须再次检查 |

## 暂不执行项

本计划确认前不修改业务代码、不删除订单、不运行 Maven/前端构建或测试。进入实施阶段后，按项目规范先补测试，再由用户确认是否执行验证命令。
