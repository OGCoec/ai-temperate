# ADR：会员支付 Hybrid ID 统一使用 22 位 Base64URL

- 状态：已接受
- 日期：2026-08-21
- 范围：会员订单、支付回调、模拟支付、Redis、RabbitMQ 与测试产物

## 背景

会员订单与支付回调主键由 `HybridSemaphoreIdWorker` 生成，数据库以固定 16 字节 `BYTEA` 保存。项目现有 128 位 Hybrid ID 的公共边界主要使用 22 字符无填充 Base64URL；此前会员支付单独使用 26 字符 ULID 是需求理解偏差，会造成同类资源出现不必要的编码例外。

## 决策

会员订单 ID、回调 ID 及模拟支付 `out_trade_no` 统一采用：

```text
16 字节 BYTEA -> Base64.getUrlEncoder().withoutPadding() -> 22 字符
^[A-Za-z0-9_-]{22}$
```

所有入口必须使用 `HybridBase64UrlCodec` 解码并回编码校验规范形式，拒绝填充字符、标准 Base64 的 `+`/`/`、非规范尾部位和全零值。Base64URL 只是编码，不是加密或授权；用户订单接口仍执行资源所有权校验，模拟回调仍执行独立密钥、商户和业务字段校验。

数据库二进制列不迁移、不重写。Navicat 可读性由数据库函数与只读视图提供，不在业务表中冗余保存文本 ID。

用户 API Key 既有的 26 位 ULID 不属于本 ADR 范围，继续遵循其独立 ADR。

## 影响

- 同一订单在 API、Redis、RabbitMQ、模拟支付与 SQL 视图中使用完全相同的 22 位文本。
- 切换前仅存在本地开发数据和测试产物，因此采用一次性硬切换，不提供会员 ULID 双读。
- 切换时必须清理或自然淘汰旧会员支付 Redis Key、RabbitMQ 消息和 JMeter 产物，避免把 26 位旧文本误判为新订单。

## 回滚

回滚必须同时覆盖 Controller、Service、Redis、RabbitMQ、模拟支付与测试资产，不允许只回滚单一边界。数据库仍是相同 16 字节值，因此编码回滚不需要改写表数据；但必须先排空使用新文本的异步消息和缓存。
