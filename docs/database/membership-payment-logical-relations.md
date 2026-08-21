# 会员支付逻辑关系与恢复说明

会员支付表不使用 PostgreSQL 物理外键，这是项目当前明确接受的一致性风险。应用在创建 `membership_order` 前必须确认 `userloginidentity.id` 存在；写入 `membership_payment_callback` 前必须批量解析并校验对应订单。

逻辑关系如下：

- `membership_order.login_identity_id -> userloginidentity.id`
- `membership_payment_callback.order_id -> membership_order.id`

回调审计表同时使用 `UNIQUE(order_id)` 和 `UNIQUE(provider_trade_no)`：前者保证一张业务订单最多形成一条支付回调事实，后者阻止同一第三方流水号跨订单复用。Redis 的订单级与流水级原子幂等只用于快速拦截，Redis 工件缺失或过期后仍由这两个 PostgreSQL 唯一约束进行最终裁决。通过格式与认证校验的重复通知统一返回 `200 success`，但不得新增回调、改写订单或重复触发退款条件。

两张表的 Hybrid ID 在数据库中始终以 16 字节 `BYTEA` 保存。API、模拟支付 `out_trade_no`、Redis 与 RabbitMQ 统一使用 22 字符无填充 Base64URL；Navicat 人工排障应查询 `membership_order_readable` 与 `membership_payment_callback_readable`，禁止从界面显示的 BLOB 文本猜测订单号。两个视图由 `sql/migrations/029_create_membership_payment_readable_views.sql` 创建，视图中的订单关联值可直接按文本相等连接。

删除登录身份时，必须先停止该身份的新下单请求，并依次处理支付回调审计保留策略、会员订单、会员额度及登录身份。默认不删除已经形成支付审计事实的回调和订单；如确需清理，必须先导出审计记录，再按“回调 -> 订单 -> 登录身份”的顺序执行有界、带条件的删除。

状态恢复以 PostgreSQL 中 `membership_order.state_version` 与 Redis 订单快照版本比较：Redis 版本更高时进入脏队列重新批量持久化；Redis 缺失时以 PostgreSQL 重建非终态快照；终态数据库记录不得由更低版本覆盖。孤儿数据使用 `sql/checks/membership_payment_orphans.sql` 巡检，发现后先隔离写流量，再依据支付回调审计和日志确认真实归属，禁止直接猜测或级联删除。
