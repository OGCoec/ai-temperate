# ADR：API Key 使用 128 位 Hybrid ULID 主键

- 状态：已接受
- 日期：2026-08-19
- 范围：用户 API Key、模型授权与外部 API 调用用量

## 背景

API Key、模型授权和调用用量原先使用 PostgreSQL 自增 `BIGINT`。数据库已经清空并重新建表，
本次需要统一改为 `HybridSemaphoreIdWorker` 生成的 16 字节 ID，同时让 API Key 路径使用固定
26 字符 ULID，而不是继续暴露由递增 Long 编码得到的 11 字符 Base64URL。

## 决策

`user_api_key.id`、`user_api_key_model.user_api_key_id`、`ai_model_api_usage.id`、
`ai_model_api_usage_detail.id` 与 `ai_model_api_usage_detail.usage_id` 使用 16 字节 `BYTEA`。
API Key 对外使用严格大写 Crockford Base32 ULID：

```text
^[0-7][0-9A-HJKMNP-TV-Z]{25}$
```

PathVariable 由专用 Spring Converter 解码为 `byte[16]`，HTTP DTO 在进入 ObjectMapper 前先由
`HybridUlidCodec` 编码为 String。禁止注册全局 `byte[]` Jackson 序列化器。AI 模型等 Long
资源继续使用 11 字符 Base64URL，其他 Hybrid 资源现有 22 字符 Base64URL 契约保持不变。

ULID 不是加密或授权边界。所有 API Key 管理和调用记录接口仍必须使用当前登录用户执行资源级
所有权校验；日志、响应和监控标签禁止输出内部二进制 ID、完整 Key 或摘要。

## 影响与风险

- 旧 11 字符 API Key 路径不再兼容，后端、Cloudflare Worker、H5 与 Android 必须协调发布。
- Redis API Key 认证快照升级到 v2 命名空间，旧 v1 值仅等待 TTL 过期。
- PostgreSQL 不使用物理外键，逻辑关联继续依靠事务、唯一约束、普通索引和孤儿检查 SQL。
- Hybrid Worker 的节点配置必须保持唯一；时钟回拨时继续拒绝发号。

## 回滚

数据库为空且不提供旧 ID 迁移，因此回滚只能在维护窗口内重新建库并整体回滚后端、Worker、H5
和 Android。禁止只回滚某一层后继续接受不匹配的公共 ID 契约。
