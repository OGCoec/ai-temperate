# 会员支付 wxpay 修复实施清单

## 阶段 0：证据和契约

- [ ] 固化问题订单的时间线和脱敏 traceId。
- [ ] 确认六号 wxpay 的 `trade_no`、`pay_type`、`pay_info` 和金额字段。
- [ ] 确认 `jump`、`qrcode`、未知类型的支持策略。
- [ ] 停止通过手动删除数据库/Redis 行清理问题订单。

## 阶段 1：502 与响应观测

- [ ] 增加 `liuhao_create_payload_validation` 结构化日志。
- [ ] 保留异常发生前已解析的 Provider 观测结果。
- [ ] 修正 `trade_no_present=true` 被异常路径记录成 `false` 的问题。
- [ ] 为 HTTP、网络、签名、业务码、类型和金额建立稳定 reason。

## 阶段 2：创建幂等和前端行为

- [ ] 区分确定未创建、已创建但入口不可用、结果未知。
- [ ] 已有 `trade_no` 时禁止同一订单重复 create。
- [ ] 仅在用户确认后调用 `/cancel`。
- [ ] 校验 502/replay unavailable 不会触发自动创建循环。

## 检查点 A

- [ ] wxpay 可用入口流程通过。
- [ ] wxpay 已有交易号但入口不支持时不重复创建。
- [ ] alipay 流程无回归。

## 阶段 3：人工关单时间

- [ ] 将 `startManualClosing()` deadline 改为 `expiresAt + closingDuration`。
- [ ] 保留立即进入 CLOSING 的行为，除非产品另行确认。
- [ ] 补 15 秒后人工取消的 deadline 回归用例。

## 阶段 4：状态机保护

- [ ] 在 `start_closing.lua` 校验最低 deadline。
- [ ] 在 `finalize_closing.lua` 校验最低 deadline。
- [ ] 早期消息只能重试或重新调度，不能 CLOSED。
- [ ] 保持 callback marker、Provider PAID 和重复消息的 CAS 保护。

## 阶段 5：审计和历史订单

- [ ] 增加 cancel 审计字段和 traceId 关联。
- [ ] 查询 `CLOSING` 且 deadline 早于正确边界的订单。
- [ ] 通过受控流程修复 CLOSING 订单并重发边界调度。
- [ ] 对已提前 CLOSED 订单先查 Provider，再做对账/退款/告警。

## 阶段 6：验证和发布

- [ ] 补齐 Liuhao 响应分类测试。
- [ ] 补齐 Redis Lua 边界和并发测试。
- [ ] 补齐 502 到最终关单的端到端场景。
- [ ] 灰度监控重复 create、trade_no 丢失、TOO_EARLY、提前 CLOSED 和死信。
- [ ] 确认 alipay 与正常 wxpay 无回归后扩大流量。
