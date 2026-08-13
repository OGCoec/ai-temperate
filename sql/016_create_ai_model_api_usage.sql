BEGIN;

CREATE TABLE ai_model_api_usage (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    key_digest BYTEA NOT NULL,
    ai_model_id BIGINT NOT NULL,
    billing_status SMALLINT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    cached_prompt_tokens BIGINT,
    charged_quota_minor BIGINT,
    finish_reason VARCHAR(64),
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMPTZ,

    CONSTRAINT pk_ai_model_api_usage
        PRIMARY KEY (id),
    CONSTRAINT chk_ai_model_api_usage_key_digest
        CHECK (OCTET_LENGTH(key_digest) = 32),
    CONSTRAINT chk_ai_model_api_usage_billing_status
        CHECK (billing_status BETWEEN 0 AND 4),
    CONSTRAINT chk_ai_model_api_usage_tokens
        CHECK (
            (prompt_tokens IS NULL OR prompt_tokens >= 0)
            AND (completion_tokens IS NULL OR completion_tokens >= 0)
            AND (cached_prompt_tokens IS NULL OR cached_prompt_tokens >= 0)
        ),
    CONSTRAINT chk_ai_model_api_usage_cached_tokens
        CHECK (
            cached_prompt_tokens IS NULL
            OR prompt_tokens IS NULL
            OR cached_prompt_tokens <= prompt_tokens
        ),
    CONSTRAINT chk_ai_model_api_usage_charged_quota
        CHECK (
            charged_quota_minor IS NULL
            OR charged_quota_minor >= 0
        ),
    CONSTRAINT chk_ai_model_api_usage_finish_reason
        CHECK (
            finish_reason IS NULL
            OR (
                finish_reason = BTRIM(finish_reason)
                AND LENGTH(finish_reason) > 0
            )
        ),
    CONSTRAINT chk_ai_model_api_usage_failure_code
        CHECK (
            failure_code IS NULL
            OR (
                failure_code = BTRIM(failure_code)
                AND LENGTH(failure_code) > 0
            )
        ),
    CONSTRAINT chk_ai_model_api_usage_settled_at
        CHECK (
            settled_at IS NULL
            OR settled_at >= created_at
        )
);

-- 用户查看某个 API Key 的调用记录时，按照创建时间和主键倒序进行稳定游标分页。
CREATE INDEX idx_ai_model_api_usage_key_created_id
    ON ai_model_api_usage (
        key_digest,
        created_at DESC,
        id DESC
    );

-- 管理端按照模型统计 API 调用时先固定模型，再按照创建时间和主键倒序读取。
CREATE INDEX idx_ai_model_api_usage_model_created_id
    ON ai_model_api_usage (
        ai_model_id,
        created_at DESC,
        id DESC
    );

-- 后台恢复任务只扫描尚未结算和需要对账的少量记录，避免低基数状态产生全量普通索引。
CREATE INDEX idx_ai_model_api_usage_pending_created_id
    ON ai_model_api_usage (
        billing_status,
        created_at ASC,
        id ASC
    )
    WHERE billing_status IN (0, 3);

COMMENT ON TABLE ai_model_api_usage IS
    '外部 API Key 调用 AI 模型的核心用量和最终计费结果表；每次 HTTP 请求产生一条记录，不保存提问或回答正文';
COMMENT ON COLUMN ai_model_api_usage.id IS
    'PostgreSQL 自动递增的 API 模型用量记录主键';
COMMENT ON COLUMN ai_model_api_usage.key_digest IS
    '发起调用的 API Key 经用途隔离 HMAC-SHA256 计算得到的固定 32 字节摘要，不保存 API Key 明文';
COMMENT ON COLUMN ai_model_api_usage.ai_model_id IS
    '本次调用使用的 AI 模型 ID，逻辑关联 ai_model.id，不建立物理外键';
COMMENT ON COLUMN ai_model_api_usage.billing_status IS
    '计费状态：0=RESERVED，1=SETTLED，2=FAILED_REFUNDED，3=RECONCILE_REQUIRED，4=REFUNDED';
COMMENT ON COLUMN ai_model_api_usage.prompt_tokens IS
    '上游返回的实际输入 Token 数量；尚未取得最终用量时允许为空';
COMMENT ON COLUMN ai_model_api_usage.completion_tokens IS
    '上游返回的实际输出 Token 数量；尚未取得最终用量时允许为空';
COMMENT ON COLUMN ai_model_api_usage.cached_prompt_tokens IS
    '输入 Token 中命中上游缓存的 Token 数量；上游未提供缓存用量时允许为空';
COMMENT ON COLUMN ai_model_api_usage.charged_quota_minor IS
    '本次请求最终实际扣除的内部额度最小单位；尚未完成结算时允许为空';
COMMENT ON COLUMN ai_model_api_usage.finish_reason IS
    '模型请求的受控结束原因，例如 STOP、LENGTH、CLIENT_CANCELLED 或 UPSTREAM_ERROR';
COMMENT ON COLUMN ai_model_api_usage.failure_code IS
    '可安全记录的受控失败代码，禁止保存完整异常堆栈或上游敏感响应';
COMMENT ON COLUMN ai_model_api_usage.created_at IS
    '预扣费成功并在同一事务中创建本次 API 模型用量记录的时间';
COMMENT ON COLUMN ai_model_api_usage.settled_at IS
    '完成最终结算、退款或转入待对账状态的时间；尚未处理完成时为空';

COMMENT ON INDEX idx_ai_model_api_usage_key_created_id IS
    '支持按照 API Key 和创建时间倒序稳定分页查询调用记录';
COMMENT ON INDEX idx_ai_model_api_usage_model_created_id IS
    '支持按照 AI 模型和创建时间倒序统计及查询 API 调用记录';
COMMENT ON INDEX idx_ai_model_api_usage_pending_created_id IS
    '支持后台任务按创建时间扫描超时预扣和待对账记录';

COMMIT;

