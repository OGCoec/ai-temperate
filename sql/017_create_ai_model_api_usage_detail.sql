BEGIN;

CREATE TABLE ai_model_api_usage_detail (
    id BYTEA NOT NULL,
    usage_id BYTEA NOT NULL,
    vendor_snapshot VARCHAR(128) NOT NULL,
    is_stream BOOLEAN NOT NULL,
    reserved_quota_minor BIGINT NOT NULL,
    settlement_delta_minor BIGINT,

    CONSTRAINT pk_ai_model_api_usage_detail
        PRIMARY KEY (id),
    CONSTRAINT uk_ai_model_api_usage_detail_usage
        UNIQUE (usage_id),
    CONSTRAINT chk_ai_model_api_usage_detail_id_length
        CHECK (OCTET_LENGTH(id) = 16),
    CONSTRAINT chk_ai_model_api_usage_detail_usage_id_length
        CHECK (OCTET_LENGTH(usage_id) = 16),
    CONSTRAINT chk_ai_model_api_usage_detail_vendor
        CHECK (
            vendor_snapshot = BTRIM(vendor_snapshot)
            AND LENGTH(vendor_snapshot) > 0
        ),
    CONSTRAINT chk_ai_model_api_usage_detail_reserved_quota
        CHECK (reserved_quota_minor >= 0)
);

COMMENT ON TABLE ai_model_api_usage_detail IS
    '外部 API Key 模型调用的一对一计费详情表；保存上游调用信息、预扣额度和最终结算差额';
COMMENT ON COLUMN ai_model_api_usage_detail.id IS
    'HybridSemaphoreIdWorker 生成的固定 16 字节 API 模型用量详情主键';
COMMENT ON COLUMN ai_model_api_usage_detail.usage_id IS
    '逻辑关联 ai_model_api_usage.id 的固定 16 字节 Hybrid ID；唯一约束保证每条核心用量只有一条计费详情，不建立物理外键';
COMMENT ON COLUMN ai_model_api_usage_detail.vendor_snapshot IS
    '本次请求实际使用的上游供应商名称快照，用于调用统计和问题排查';
COMMENT ON COLUMN ai_model_api_usage_detail.is_stream IS
    '是否使用 SSE 流式响应；TRUE 表示流式请求，FALSE 表示非流式请求';
COMMENT ON COLUMN ai_model_api_usage_detail.reserved_quota_minor IS
    '调用上游模型之前已经成功预扣的内部额度最小单位';
COMMENT ON COLUMN ai_model_api_usage_detail.settlement_delta_minor IS
    '最终实际扣费减去预扣额度；正数表示补扣，负数表示退还，零表示无需调整，NULL 表示尚未结算';

COMMENT ON CONSTRAINT uk_ai_model_api_usage_detail_usage
    ON ai_model_api_usage_detail IS
    '保证 ai_model_api_usage 与 ai_model_api_usage_detail 保持一对一逻辑关系，并提供 usage_id 精确查询索引';

COMMIT;
