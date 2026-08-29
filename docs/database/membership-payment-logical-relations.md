# 会员支付逻辑关系与恢复说明

会员支付表不使用 PostgreSQL 物理外键，这是项目当前明确接受的一致性风险。应用在创建 `membership_order` 前必须确认 `userloginidentity.id` 存在；写入 `membership_payment_callback` 前必须批量解析并校验对应订单。

逻辑关系如下：

- `membership_order.login_identity_id -> userloginidentity.id`
- `membership_payment_callback.order_id -> membership_order.id`

回调审计表同时使用 `UNIQUE(order_id)` 和 `UNIQUE(provider_trade_no)`：前者保证一张业务订单最多形成一条支付回调事实，后者阻止同一第三方流水号跨订单复用。Redis 的订单级与流水级原子幂等只用于快速拦截，Redis 工件缺失或过期后仍由这两个 PostgreSQL 唯一约束进行最终裁决。通过格式与认证校验的重复通知统一返回 `200 success`，但不得新增回调、改写订单或重复触发退款条件。

`membership_order.entitlement_resolution` 与 `entitlement_resolved_at` 记录订单权益裁决。两列必须同时为空或同时非空；`APPLIED` 表示订单、套餐额度和 callback resolution 已在同一 PostgreSQL 本地事务提交，`NOT_GRANTED` 表示未付款订单已经进入 CANCELLED/CLOSED 且没有发放权益，`REFUND_REQUIRED` 表示终态后又确认真实付款、不得发放且外部退款可以在事务提交后执行，`LEGACY_NOT_GRANTED` 表示迁移前的历史 PAID 订单不自动补发。只有 `NOT_GRANTED → REFUND_REQUIRED` 是允许的覆盖迁移；`NOT_GRANTED → APPLIED` 被禁止。Redis 订单快照和公开订单 JSON 不携带这些内部字段。

同一用户只允许一笔活动订单。创建事务先解析 UUIDv4 幂等重放，再按用户取得事务级 advisory lock，锁内检查活动订单；部分唯一索引最终约束 `PENDING_PAYMENT`、`CLOSING` 以及 `PAID + entitlement_resolution IS NULL`。Redis 已终态但 PostgreSQL 尚未刷盘时仍返回 409，这是为避免第二笔订单越过旧订单的回调或权益结算窗口而接受的保守行为。

两张表的 Hybrid ID 在数据库中始终以 16 字节 `BYTEA` 保存。API、模拟支付 `out_trade_no`、Redis 与 RabbitMQ 统一使用 22 字符无填充 Base64URL；Navicat 人工排障应查询 `membership_order_readable` 与 `membership_payment_callback_readable`，禁止从界面显示的 BLOB 文本猜测订单号。两个视图由 `sql/migrations/029_create_membership_payment_readable_views.sql` 创建，视图中的订单关联值可直接按文本相等连接。

删除登录身份时，必须先停止该身份的新下单请求，并依次处理支付回调审计保留策略、会员订单、会员额度及登录身份。默认不删除已经形成支付审计事实的回调和订单；如确需清理，必须先导出审计记录，再按“回调 -> 订单 -> 登录身份”的顺序执行有界、带条件的删除。

状态恢复以 PostgreSQL 中 `membership_order.state_version` 与 Redis 订单快照版本比较：Redis 版本更高时进入脏队列重新批量持久化；Redis 缺失时以 PostgreSQL 重建非终态快照；终态数据库记录不得由更低版本覆盖。孤儿数据使用 `sql/checks/membership_payment_orphans.sql` 巡检，发现后先隔离写流量，再依据支付回调审计和日志确认真实归属，禁止直接猜测或级联删除。

## BAR 沙箱 Provider 简化边界

BAR 继续使用 `membership_order.provider_trade_no` 保存字符串交易号，并使用 `membership_payment_callback` 保存支付成功审计事实；新增的权益裁决字段属于主项目内部一致性边界，不改变 BAR 协议。当前环境通过 `app.membership-payment.default-provider` 在 `LOCAL_SIMULATOR` 与 `BAR` 之间二选一，Provider 类型不写入订单；因此切换前必须等待或清理旧 Provider 的全部非终态订单，禁止在同一环境同时启用两种 Provider。

BAR 的 `checkoutSubmission` 只在一次 `no-store` payment-attempts 响应和紧随其后的浏览器 Form POST 中短暂存在；不写 PostgreSQL、Redis、RabbitMQ、日志或浏览器 Storage。BAR 回调先按历史 `key_version` 验签，再通过 `/api/pay/query` 获取已签名 `finished_at`，最终仍写入原 Redis 回调队列；主动查询和关单继续复用原 RabbitMQ 队列。`REFUND_REQUIRED` 在回调 resolution 本地事务提交后直接调用 BAR 幂等 `/api/pay/refund`，失败由原 Redis 回调租约恢复重试，不保存本地退款流水，最终退款状态以 BAR `/api/pay/query` 为准。

BAR 只提供模拟支付和模拟退款，不发生真实资金行为；主项目确认有效支付后，会在 PostgreSQL 本地事务中把订单收敛为 `PAID`、发放目标套餐完整额度、写入一个月会员到期时间并解析 callback。新额度周期保持未激活，直到 API Key、H5/Android 文本、图片或视频请求第一次成功进入预扣事务时，才从该次调用时间开始七天周期并扣除本次额度。
