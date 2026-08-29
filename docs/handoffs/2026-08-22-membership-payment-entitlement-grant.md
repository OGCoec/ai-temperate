# 会员支付权益原子发放与首次使用周期交接

## 最终链路

```text
BAR notify 验签与 query 反查
→ Redis callback marker
→ Callback Worker 保存回调审计
→ Redis 订单原子迁移 PAID
→ PostgreSQL 本地事务：
   订单补写 PAID
   + 目标会员套餐完整额度
   + 一个月会员到期时间
   + 订单 entitlement APPLIED
   + callback resolution APPLIED
→ 事务提交后删除用户资料缓存
→ Redis complete 删除 marker
```

外部退款路径必须先把订单权益和 callback 同时裁决为 `REFUND_REQUIRED`，事务提交后才调用 BAR 幂等退款。`CANCELLED`、`CLOSED`、硬关闭截止外付款、金额或支付方式不匹配均不得发放套餐。

个人购买目标仍只允许 `GO、PLUS、PRO、MAX`，并只允许 `GO → PLUS → PRO → MAX` 的向上升级；`FREE` 不可购买，`EDU、TEAM` 不能通过个人购买或升级 API 进入。

## 数据不变量

支付成功后：

```text
membership_tier         = 订单目标套餐
membership_expires_at   = UTC(paidAt).plusMonths(1)
quota_balance_minor     = 目标套餐完整额度
quota_period_started_at = NULL
quota_period_ends_at    = UTC(paidAt)
```

第一次成功进入计费预扣的 API Key、H5/Android 文本、图片或视频请求，再在同一额度行锁和事务中写入：

```text
quota_period_started_at = firstUsageAt
quota_period_ends_at    = firstUsageAt + 7 天
quota_balance_minor     = 套餐完整额度 - 本次预扣
```

幂等重放在额度查询前返回；鉴权失败、模型不允许、额度不足或事务回滚不会持久化周期起点。

## 单活动订单

创建订单先查 UUIDv4 幂等键，再按用户取得 PostgreSQL 事务级 advisory lock。锁内若已有以下任一订单则返回 `MEMBERSHIP_ORDER_STATE_CONFLICT`（HTTP 409），不自动关闭旧订单：

```text
PENDING_PAYMENT
CLOSING
PAID 且 entitlement_resolution IS NULL
```

部分唯一索引是跨实例最终并发裁决。`CANCELLED`、`CLOSED` 或 `PAID + APPLIED` 落库后才释放占用。

## 迁移顺序

1. 执行 `030_add_membership_order_entitlement_resolution.sql`；若已有重复 PENDING/CLOSING，迁移直接失败并要求人工裁决。
2. 迁移把历史 PAID 回填为 `LEGACY_NOT_GRANTED`，不补发额度；既有退款回调回填为 `REFUND_REQUIRED`。
3. 以自动提交方式单独执行 `031_create_membership_order_single_active_index.sql`；该文件只有一条 `CREATE UNIQUE INDEX CONCURRENTLY` 可执行语句，禁止包裹在显式事务中。

公开订单 JSON、Redis Key/快照、RabbitMQ 消息、BAR Form/query/close/refund/notify 协议均不增加权益字段。
