BEGIN;

CREATE TABLE ai_model_usage_detail (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    usage_id BYTEA NOT NULL,
    conversation_id BYTEA NOT NULL,
    conversation_message_id BIGINT,
    idempotency_key_digest BYTEA NOT NULL,
    upstream_request_id VARCHAR(128),
    vendor_snapshot VARCHAR(128) NOT NULL,
    is_stream BOOLEAN NOT NULL,
    estimated_prompt_tokens BIGINT NOT NULL,
    max_output_tokens BIGINT NOT NULL,
    input_ratio_snapshot NUMERIC(20, 8) NOT NULL,
    cached_input_ratio_snapshot NUMERIC(20, 8) NOT NULL,
    output_ratio_snapshot NUMERIC(20, 8) NOT NULL,
    reserved_quota_minor BIGINT NOT NULL,
    settlement_delta_minor BIGINT,

    CONSTRAINT pk_ai_model_usage_detail
        PRIMARY KEY (id),
    CONSTRAINT uk_ai_model_usage_detail_usage_id
        UNIQUE (usage_id),
    CONSTRAINT uk_ai_model_usage_detail_idempotency_digest
        UNIQUE (idempotency_key_digest),
    CONSTRAINT chk_ai_model_usage_detail_usage_id_length
        CHECK (OCTET_LENGTH(usage_id) = 16),
    CONSTRAINT chk_ai_model_usage_detail_conversation_id_length
        CHECK (OCTET_LENGTH(conversation_id) = 16),
    CONSTRAINT chk_ai_model_usage_detail_conversation_message_id
        CHECK (
            conversation_message_id IS NULL
            OR conversation_message_id > 0
        ),
    CONSTRAINT chk_ai_model_usage_detail_idempotency_digest_length
        CHECK (OCTET_LENGTH(idempotency_key_digest) = 32),
    CONSTRAINT chk_ai_model_usage_detail_upstream_request_id
        CHECK (
            upstream_request_id IS NULL
            OR (
                upstream_request_id = BTRIM(upstream_request_id)
                AND LENGTH(upstream_request_id) > 0
            )
        ),
    CONSTRAINT chk_ai_model_usage_detail_vendor
        CHECK (
            vendor_snapshot = BTRIM(vendor_snapshot)
            AND LENGTH(vendor_snapshot) > 0
        ),
    CONSTRAINT chk_ai_model_usage_detail_estimated_prompt_tokens
        CHECK (estimated_prompt_tokens >= 0),
    CONSTRAINT chk_ai_model_usage_detail_max_output_tokens
        CHECK (max_output_tokens > 0),
    CONSTRAINT chk_ai_model_usage_detail_input_ratio
        CHECK (input_ratio_snapshot >= 0),
    CONSTRAINT chk_ai_model_usage_detail_cached_input_ratio
        CHECK (cached_input_ratio_snapshot >= 0),
    CONSTRAINT chk_ai_model_usage_detail_output_ratio
        CHECK (output_ratio_snapshot >= 0),
    CONSTRAINT chk_ai_model_usage_detail_reserved_quota
        CHECK (reserved_quota_minor >= 0)
);

CREATE INDEX idx_ai_model_usage_detail_conversation_id
    ON ai_model_usage_detail (conversation_id);

CREATE INDEX idx_ai_model_usage_detail_message_id
    ON ai_model_usage_detail (conversation_message_id)
    WHERE conversation_message_id IS NOT NULL;

COMMENT ON TABLE ai_model_usage_detail IS
    '模型用量记录的一对一低频详情，保存幂等证据、上游请求信息、预扣依据和计费倍率快照';
COMMENT ON COLUMN ai_model_usage_detail.id IS
    '详情表自身 BIGINT 自增主键';
COMMENT ON COLUMN ai_model_usage_detail.usage_id IS
    '逻辑关联 ai_model_usage.id 的 16 字节 Hybrid ID；唯一约束保证一条用量只有一条详情';
COMMENT ON COLUMN ai_model_usage_detail.conversation_id IS
    '逻辑关联 ai_conversation.id 的固定 16 字节会话 ID；预扣时写入，不建立物理外键';
COMMENT ON COLUMN ai_model_usage_detail.conversation_message_id IS
    '成功完成后逻辑关联 ai_conversation_message.id；中断、失败或待对账请求保持为空';
COMMENT ON COLUMN ai_model_usage_detail.idempotency_key_digest IS
    '对业务命名空间、用户 ID 与前端 Idempotency-Key 执行 HMAC-SHA256 后的 32 字节摘要';
COMMENT ON COLUMN ai_model_usage_detail.upstream_request_id IS
    '上游模型厂商返回的请求 ID；上游未返回时为 NULL';
COMMENT ON COLUMN ai_model_usage_detail.vendor_snapshot IS
    '发生调用时的模型厂商快照，用于历史计费审计';
COMMENT ON COLUMN ai_model_usage_detail.is_stream IS
    'TRUE 表示 SSE 流式调用，FALSE 表示普通非流式 HTTP 响应';
COMMENT ON COLUMN ai_model_usage_detail.estimated_prompt_tokens IS
    '预扣前由本地计数器估算的输入 Token';
COMMENT ON COLUMN ai_model_usage_detail.max_output_tokens IS
    '本次请求声明的最大输出 Token，用于计算预扣额度';
COMMENT ON COLUMN ai_model_usage_detail.input_ratio_snapshot IS
    '预扣时采用的普通输入 Token 计费倍率快照';
COMMENT ON COLUMN ai_model_usage_detail.cached_input_ratio_snapshot IS
    '预扣或结算时采用的上游缓存输入 Token 计费倍率快照';
COMMENT ON COLUMN ai_model_usage_detail.output_ratio_snapshot IS
    '预扣时采用的输出 Token 计费倍率快照';
COMMENT ON COLUMN ai_model_usage_detail.reserved_quota_minor IS
    '模型调用前已经成功预扣的额度最小单位整数值';
COMMENT ON COLUMN ai_model_usage_detail.settlement_delta_minor IS
    '最终实际额度减去预扣额度；正数补扣、负数退还、NULL 表示尚未结算';

COMMENT ON INDEX idx_ai_model_usage_detail_conversation_id IS
    '支持按会话定位预扣、完成、中断和待对账的全部模型请求';
COMMENT ON INDEX idx_ai_model_usage_detail_message_id IS
    '支持从已经持久化的完整消息反查用量详情，并排除未完成请求的空值';

COMMENT ON CONSTRAINT uk_ai_model_usage_detail_usage_id
    ON ai_model_usage_detail IS
    '保证核心用量记录与详情记录保持一对一逻辑关系，并提供 usage_id 精确查询索引';
COMMENT ON CONSTRAINT uk_ai_model_usage_detail_idempotency_digest
    ON ai_model_usage_detail IS
    '保证同一用户同一模型调用幂等键只能成功创建一条用量详情';

COMMIT;
