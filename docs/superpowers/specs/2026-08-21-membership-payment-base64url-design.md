# 会员支付 Hybrid ID 统一为 Base64URL 设计

## 背景与问题

会员订单与支付回调主键均由 `HybridSemaphoreIdWorker` 生成，以固定 16 字节
`BYTEA` 保存。项目已有 `HybridBase64UrlCodec`，并将 AI 会话、生成任务、用量与
邮箱检查任务等 128 位 Hybrid ID 统一编码为 22 字符无填充 Base64URL。

当前尚未提交的会员支付实现单独使用 `HybridUlidCodec`，导致订单 API、支付
`out_trade_no`、Redis、RabbitMQ 和测试产物出现 26 字符 ULID。这一例外不符合
项目对 128 位 Hybrid 业务 ID 的统一编码要求，应在会员支付功能发布前移除。

## 决策

会员支付所有 128 位 Hybrid ID 统一使用现有 `HybridBase64UrlCodec`：

```text
16 字节 BYTEA
-> Base64 URL-safe without padding
-> 固定 22 字符
-> ^[A-Za-z0-9_-]{22}$
```

编码只改变文本表示，不改变 ID 的 128 位二进制值、数据库主键、索引或表间逻辑
关联。Base64URL 不提供加密、认证或防枚举能力，资源所有权和回调安全校验保持不变。

## 修改范围

以下边界必须同时切换，禁止同一订单出现两套公共 ID：

- 订单创建、查询、取消和支付发起接口中的 `orderId`、PathVariable 与 `Location`。
- 模拟支付及未来真实支付请求的商户订单号 `out_trade_no`。
- 支付回调的 `out_trade_no` 规范校验与解码。
- `MembershipOrderRedisId`、`PaymentCallbackRedisId` 及所有会员支付 Redis Key。
- 订单快照、回调队列、callback marker、dirty set 中保存的文本 ID。
- RabbitMQ 信封中的订单 ID、回调 ID 和消息 ID。
- Mapper 边界的十六进制/二进制转换、日志脱敏和批量持久化输入。
- OpenAPI 长度、正则、示例以及所有 Java、Lua、Web、Mapper 和 JMeter 测试。
- JMeter 场景 CSV、SQL 验收脚本和运行产物中的订单 ID 校验。

本次不修改用户 API Key 的 26 位 ULID。`HybridUlidCodec` 是否继续用于 API Key
属于独立决策，不与会员支付改动绑定。

## 数据库可视化

原表继续保存 `BYTEA(16)`。数据库脚本正式纳入现有只读函数：

```sql
hybrid_id_to_base64url(BYTEA) RETURNS TEXT
```

建立两个只读视图：

- `membership_order_readable`：使用 `id_base64url` 替代不可读的订单 BLOB，并保留
  订单全部业务字段。
- `membership_payment_callback_readable`：提供 `id_base64url` 与
  `order_id_base64url`，并保留回调全部业务字段。

视图仅用于 Navicat 排障、验收和关联查询，不替代 Mapper 对原表的读写，也不在
视图中暴露密钥、Token 或回调原始敏感报文。

## 兼容与本地数据处理

会员支付功能尚未接入真实支付平台，也没有已发布的外部 26 位订单号，因此采用
一次性硬切换，不引入 ULID/Base64URL 双读兼容。

PostgreSQL 现有订单和回调不需要迁移，因为同一 `BYTEA` 可直接得到新的 22 位
文本。已有本地 Redis Key、RabbitMQ 消息与 JMeter 运行产物包含 ULID 文本，切换
后不能继续作为有效测试输入。实施时只清理明确的会员支付测试命名空间和测试队列，
不得使用 `KEYS *`、不得删除其他业务数据，数据库测试订单默认保留。

## 验证要求

- 为会员订单与回调公共 ID 先添加失败测试，证明 26 位 ULID 被拒绝、22 位规范
  Base64URL 可往返还原同一 16 字节值。
- 创建订单响应、PathVariable、Redis、RabbitMQ、回调 `out_trade_no` 必须使用同一
  22 位字符串。
- PostgreSQL 函数输出必须与 Java `HybridBase64UrlCodec.encode()` 字节级一致。
- 两个可读视图不得包含 `BYTEA` 类型的 ID 列，订单与回调的 `order_id_base64url`
  必须可直接等值关联。
- 更新后的每个 JMeter 测试条件仍至少执行 3 个测试用例。
- 先通过相关 Java/SQL 契约测试与 `loadtest-fast`，再恢复实时 JMeter 状态机测试。

## 回滚

代码发布前可恢复原分支。若切换后仅在本地测试环境发现问题，回滚应用并清理本次
运行产生的会员支付 Redis/RabbitMQ 测试数据即可；PostgreSQL 二进制主键无需回滚。
禁止只回滚 Controller、Redis 或回调中的单一边界，否则同一订单会出现不兼容的
文本 ID。
