# 会员支付 22 位 Base64URL ID 实施计划

> **执行目标：** 会员订单、支付回调、Redis、RabbitMQ、模拟支付参数和 JMeter 统一使用 `BYTEA(16) -> Base64URL without padding` 的 22 字符公开 ID；用户 API Key 的 26 位 ULID 保持不变。

## 任务一：用失败测试锁定公开 ID 契约

- 修改会员订单 Redis ID、Web Converter、Web 契约和会员支付 Service/Rabbit 测试夹具，预期 22 位规范 Base64URL。
- 增加 26 位 ULID、带 `=`、非法字符、零值和非 16 字节值的拒绝断言。
- 先运行最小测试集并确认旧实现失败。

## 任务二：替换会员支付 Java 编解码边界

- 将会员支付包、会员订单 PathVariable、回调 ID、Redis Key、RabbitMQ Envelope 和持久化转换从 `HybridUlidCodec` 切换到 `HybridBase64UrlCodec`。
- 更新变量名、JavaDoc、OpenAPI 长度与正则；不注册全局 `byte[]` Jackson 序列化器。
- 保持数据库 ID、领域模型及 Mapper 的 16 字节二进制表示不变。

## 任务三：更新外部测试资产与文档

- 将会员支付 JMeter 默认 ID、正则和脚本校验切换为 22 位 Base64URL。
- 更新会员支付 SQL 列注释与逻辑关系文档，撤销“会员支付使用 ULID”的特殊 ADR。
- 全仓搜索，确认会员支付范围不再引用 `HybridUlidCodec`，API Key ULID 代码不变。

## 任务四：补 PostgreSQL 可读函数与视图

- 以幂等迁移固化 `hybrid_id_to_base64url(bytea)`，严格校验 16 字节输入。
- 创建 `membership_order_readable` 与 `membership_payment_callback_readable`，公开 22 位 Base64URL ID 并保留可读业务列，不暴露原始 `BYTEA` ID 列。
- 在本地 PostgreSQL 应用迁移，验证视图长度、字符集和订单/回调关联一致。

## 任务五：回归验证

- 运行会员支付相关 common、service、web、mapper 测试。
- 编译相关模块并检查 Spring 依赖注入无歧义。
- 重启本地 loadtest-fast 应用，执行受影响的会员支付快速 JMeter 场景；每个条件继续满足至少 3 个用例。
- 只有获得实际输出后才报告通过项；未执行或失败项明确列出。
