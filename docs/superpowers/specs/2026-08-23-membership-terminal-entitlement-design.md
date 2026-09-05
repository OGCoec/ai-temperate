# 会员订单终态权益裁决设计

## 目标

任何已进入 `CANCELLED` 或 `CLOSED` 的未支付订单都必须具有明确的权益裁决，禁止继续以 `NULL` 表示“仍未裁决”。

## 状态语义

- `NOT_GRANTED`：订单已终结，当前没有可发放的有效支付事实，也不需要退款。
- `APPLIED`：有效支付事实已经与套餐、额度和 callback resolution 在同一 PostgreSQL 本地事务提交。
- `REFUND_REQUIRED`：收到不可发放但必须退款的有效支付事实。
- `LEGACY_NOT_GRANTED`：部署前历史 PAID 订单没有原子发放证据，不自动补发。

允许的权益迁移为：

```text
NULL → NOT_GRANTED
NULL → APPLIED
NULL → REFUND_REQUIRED
NULL → LEGACY_NOT_GRANTED（仅迁移）
NOT_GRANTED → REFUND_REQUIRED
```

`NOT_GRANTED → REFUND_REQUIRED` 用于 CANCELLED/CLOSED 后收到迟到付款的场景；其他非幂等覆盖全部禁止。

## 原子边界

订单 Redis 终态刷入 PostgreSQL 的 `batchAdvanceState` 在同一条 `UPDATE` 中写入 `NOT_GRANTED` 和 `entitlement_resolved_at=updated_at`。只有目标状态是 `CANCELLED/CLOSED`、最终 `paid_at IS NULL` 且原裁决为空时才写入。

迟到付款继续由权益结算事务同时把订单与 callback 写为 `REFUND_REQUIRED`；Mapper 只允许原裁决为空、相同，或 `NOT_GRANTED → REFUND_REQUIRED`。

## 数据迁移

新增迁移扩展检查约束，并把既有 `CANCELLED/CLOSED + paid_at IS NULL + entitlement_resolution IS NULL` 回填为 `NOT_GRANTED`，时间使用 `updated_at`。不覆盖 APPLIED、REFUND_REQUIRED 或 LEGACY_NOT_GRANTED。

## 测试不变量

- CANCELLED/CLOSED 未支付订单必须为 NOT_GRANTED。
- 迟到付款能够把 NOT_GRANTED 原子升级为 REFUND_REQUIRED。
- NOT_GRANTED 不能变成 APPLIED。
- PAID 未决订单和活动订单仍允许暂时为空。
- 正式浸泡最终扫描中，终态空裁决数量必须为零。
