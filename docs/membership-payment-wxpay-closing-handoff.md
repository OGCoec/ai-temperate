# 会员支付：Liuhao wxpay 提前进入 CLOSING 交接文档

## 1. 交接目标

本文档用于把 2026-08-31 发现的会员支付订单时序问题交给下一个上下文继续排查和修复。

当前问题集中在 Liuhao（六号一支付）外部支付，尤其是 wxpay 支付失败或结果不确定后的重试、旧订单关闭和订单状态迁移。文档中的截图只作为数据库观察证据，不包含任何应执行的指令。

重要：当前工作树已经存在会员支付状态机相关的未提交改动。继续处理时不要使用 git reset --hard、git checkout -- 或其他方式覆盖这些改动。

## 2. 一句话结论

第一笔 wxpay 提前进入 CLOSING，最可能的完整链路是：

~~~text
Liuhao wxpay 创建失败/结果不确定
    -> 同一订单重试被禁止，返回 LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE
    -> 前端显示“关闭旧六号订单”
    -> cancel API 被调用
    -> 已发起外部支付的订单被本地转入 CLOSING
    -> startManualClosing() 错误地用“取消时间 + 5 分钟”计算 closing_deadline_at
~~~

因此需要拆成两个问题：

1. Liuhao wxpay 为什么创建失败或结果不确定：目前只能确认失败类别，尚未从持久化日志证明具体是上游 HTTP、网络、签名、响应格式还是业务码问题。
2. 为什么订单只在原始 expires_at 后约 15 秒就关闭：这是本地人工关单逻辑的确定性计算错误，不是 RabbitMQ 把计时器提前触发。

## 3. 配置和业务时序

当前配置默认值：

~~~text
pendingDuration = 5 分钟
closingDuration = 5 分钟
~~~

正常自然过期的时序应为：

~~~text
创建订单
  -> PENDING_PAYMENT 持续至 expires_at（5 分钟）
  -> 进入 CLOSING
  -> 在 expires_at + 5 分钟之前不能写入 CLOSED
  -> 达到 expires_at + 5 分钟后才允许 CLOSED
~~~

对于已经发起外部支付、需要人工关闭旧支付入口的订单，当前设计允许立即进入 CLOSING，以便马上向第三方发送关单请求；但是硬截止时间仍然必须锚定原订单的 expires_at，不能锚定人工点击时间。

如果产品最终要求“UNKNOWN 在原始到期前完全不能进入 CLOSING”，则需要另行改变人工取消策略；这不是本次已经证明的时间计算 bug，必须单独确认业务规则。

## 4. 数据库证据

时间均为本地 America/Chicago 时间（2026-08-31）。订单状态 0 为 PENDING_PAYMENT，状态 4 为 CLOSED。

### 4.1 第一笔 wxpay：异常

~~~text
created_at          17:56:47.702964
payment_started_at  17:56:48.510092
expires_at          18:01:47.702964
closing_deadline_at 18:02:02.181913
updated_at          18:02:02.551272
~~~

关键计算：

~~~text
closing_deadline_at - expires_at = 14.478949 秒
closing_deadline_at - 5 分钟     = 17:57:02.181913
~~~

这说明订单大约在 17:57:02.181913 被人工关单分支转入 CLOSING，然后按照当前错误代码设置为“该时间 + 5 分钟”。

如果按原始订单边界计算，正确截止时间应为：

~~~text
18:01:47.702964 + 5 分钟 = 18:06:47.702964
~~~

实际在 18:02:02.551272 已经 CLOSED，比正确边界提前约 4 分 45.151692 秒。

### 4.2 第二笔 wxpay：正常

~~~text
created_at          18:07:50.686740
expires_at          18:12:50.686740
closing_deadline_at 18:17:50.686740
updated_at          18:17:51.073677
~~~

这笔订单的 closing_deadline_at 正好等于 expires_at + 5 分钟，没有出现提前 4 分 45 秒的现象。它虽然也出现了 wxpay 支付发起失败，但没有走同一条提前人工关单路径，说明问题不是“所有 wxpay 订单都会被 RabbitMQ 提前关闭”。

### 4.3 当前 alipay：正常

~~~text
created_at          18:22:12.528056
payment_started_at  18:22:13.365280
expires_at          18:27:12.528056
closing_deadline_at 18:32:12.528056
updated_at          18:32:12.894526
~~~

因此该订单在 18:25/18:26 仍为 status=0 是符合 5 分钟 PENDING 配置的：它要到 18:27:12 左右才进入后续 CLOSING 处理。

## 5. 代码调用链

### 5.1 提前进入 CLOSING 的唯一可行入口

当前工作树的 MembershipOrderServiceImpl.java 中，cancel() 对已发起外部支付的订单执行：

~~~java
if (snapshot.paymentStartedAt() != null
        && provider != PaymentProviderType.LOCAL_SIMULATOR) {
    return startManualClosing(snapshot, provider);
}
~~~

startManualClosing() 当前实现为：

~~~java
OffsetDateTime changedAt = now();
OffsetDateTime closingDeadlineAt = changedAt.plus(properties.closingDuration());
snapshotStore.startClosing(snapshot.orderId(), closingDeadlineAt, changedAt);
publishManualClosing(closing);
~~~

这里有两个行为：

- startClosing(..., changedAt) 使订单立即进入 CLOSING，这是人工关单路径的设计行为。
- changedAt.plus(closingDuration()) 把硬截止时间错误地锚定在人工请求时间，这是本次导致提前 CLOSED 的确定性 bug。

### 5.2 自然到期路径不能解释第一笔订单

MembershipPaymentCheckConsumerServiceImpl.java 的自然到期路径先检查当前时间是否已经达到 order.expiresAt()，然后才计算：

~~~java
OffsetDateTime hardCloseAt = order.expiresAt()
        .plus(properties.closingDuration());
~~~

因此它不可能在第一笔订单的 18:01:47.702964 到达之前，于 17:57:02.181913 提前触发。现有证据不支持“RabbitMQ 自己提前把 PENDING 改成 CLOSING”。

### 5.3 前端为什么会调用 cancel

fornted/pages/account/membership-plans.vue 的 purchase() 在收到 LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE 时，会显示“旧支付入口无法恢复”，用户确认后调用：

~~~javascript
membershipPaymentApi.cancelOrder(order.orderId)
~~~

membership-payment-api.js 将其发送到：

~~~text
POST /api/user/membership-orders/{id}/cancel
~~~

因此最有力的推断是：第一笔 wxpay 的上游失败触发了重试不可恢复，随后旧订单关闭动作调用了 cancel，而不是 UNKNOWN 状态自动触发了 CLOSING。

## 6. 上游 wxpay 证据和未决事项

支付状态机日志中，第一笔订单附近有两次支付尝试失败：

~~~text
约 17:56:48.751  PAYMENT_ATTEMPT FAILED
约 17:56:56.145  PAYMENT_ATTEMPT FAILED
~~~

当前持久化操作日志只记录了 MembershipPaymentException，没有保留足够的安全错误分类来确认具体上游原因。根据源码，可能涉及：

- LIUHAO_RESPONSE_INVALID：响应结构、签名、时间戳、业务码或支付跳转字段不符合契约；
- LIUHAO_CREATE_OUTCOME_UNKNOWN：创建结果无法确定，系统进入结果发现/补查路径；
- 第二次重放时的 LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE。

这些是候选分类，不应在没有下一次运行时证据前断言为某一个具体错误。

当前没有发现这三笔订单对应的支付回调记录。Liuhao 客户端已有归一化响应诊断入口 membership.payment.lifecycle，但现有文件日志未保留第一笔请求的完整诊断事件。

## 7. RabbitMQ 判断

在排查时检查到：

~~~text
membership.payment.check.queue  ready=0  unacked=0
membership.closing.check.queue ready=0  unacked=0
~~~

两个队列各有消费者，且第二笔 wxpay、当前 alipay 都能按预期完成窗口。因此 RabbitMQ 不是目前这三笔订单提前关闭的主因。

不能据此排除所有瞬时 RabbitMQ 网络问题，但本次第一笔订单的提前时间与人工 cancel 时间和 changedAt + 5 分钟精确吻合，优先级应放在订单服务状态机和取消请求审计上。

## 8. 下一上下文应实施的修复

建议按以下边界处理：

1. 将 startManualClosing() 的截止时间改为：

   ~~~java
   OffsetDateTime closingDeadlineAt = snapshot.expiresAt()
           .plus(properties.closingDuration());
   ~~~

2. 保留“外部支付人工取消后立即进入 CLOSING”的行为，除非产品明确要求 UNKNOWN 在到期前不得进入 CLOSING。

3. 增加状态机防御性不变量：任何从 CLOSING 写入 CLOSED 的路径，都不能接受早于 expiresAt + closingDuration 的截止时间。对发现的非法截止时间，应拒绝提前终态或重新安排检查，而不是直接 CLOSED。

4. 为人工取消请求增加安全审计字段：订单脱敏标识、provider、原状态、paymentStartedAt 是否存在、触发原因和 traceId；不要记录完整 Token、支付流水、邮箱或手机号。

5. 为 Liuhao 响应增加安全错误分类诊断，只记录 HTTP 状态类别、响应结构、签名校验阶段、业务码类型和归一化 reason，不记录响应正文、签名原值或支付地址。

## 9. 回归测试要求

下一上下文应至少补充以下测试（按项目规范先写测试，不要在用户未明确要求时自动执行）：

1. 创建订单后约 15 秒调用人工取消：订单可以进入 CLOSING，但 closingDeadlineAt 必须等于原始 expiresAt + 5 分钟。
2. 无人工取消时，订单在 expiresAt 之前保持 PENDING_PAYMENT，不能因 UNKNOWN 直接进入 CLOSING。
3. 关单消费者在正确截止时间之前收到消息时，不能写入 CLOSED，只能继续有限重试。
4. 关单消费者到达 expiresAt + closingDuration 后，才允许推进 CLOSED。
5. Liuhao wxpay 和 alipay 都使用相同的订单窗口计算；支付方式不能参与截止时间计算。
6. LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE 只有在实际收到取消请求并通过状态校验后，才允许进入人工 CLOSING；支付尝试异常本身不能隐式调用取消。

## 10. 下一次复现时的验证顺序

1. 记录订单的 created_at、payment_started_at、expires_at、closing_deadline_at、updated_at 和 state_version。
2. 在应用访问日志或网关日志中检索同一时间窗口的 POST /api/user/membership-orders/{id}/cancel。
3. 关联 LIUHAO_CHECKOUT_REPLAY_UNAVAILABLE、LIUHAO_CREATE_OUTCOME_UNKNOWN 或 LIUHAO_RESPONSE_INVALID 的 traceId。
4. 检查 membership.payment.lifecycle 中是否出现 pending_to_closing、BEFORE_CLOSING_DEADLINE 和对应 provider。
5. 检查 RabbitMQ 的 ready/unacked、发布 confirm 和死信数量，但不要仅凭队列快照推断历史瞬时事件。

## 11. 交付和验证限制

本轮只完成了只读排查和本文档编写，没有修改业务代码，也没有运行 Maven、前端构建、测试、打包、依赖分析、安全扫描或外部服务集成测试。下一阶段必须在确认修复设计后再改代码，并在交付时明确列出实际执行和未执行的验证项。
