BEGIN;

CREATE TABLE ai_model_usage (
    id BYTEA NOT NULL,
    login_identity_id BIGINT NOT NULL,
    ai_model_id BIGINT NOT NULL,
    billing_status SMALLINT NOT NULL DEFAULT 0,
    metering_basis SMALLINT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    cached_prompt_tokens BIGINT,
    reasoning_tokens BIGINT,
    provider_cost_ticks BIGINT,
    charged_quota_minor BIGINT,
    finish_reason VARCHAR(64),
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMPTZ,

    CONSTRAINT pk_ai_model_usage
        PRIMARY KEY (id),
    CONSTRAINT chk_ai_model_usage_id_length
        CHECK (OCTET_LENGTH(id) = 16),
    CONSTRAINT chk_ai_model_usage_billing_status
        CHECK (billing_status BETWEEN 0 AND 4),
    CONSTRAINT chk_ai_model_usage_metering_basis
        CHECK (metering_basis IN (0, 1)),
    CONSTRAINT chk_ai_model_usage_prompt_tokens
        CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
    CONSTRAINT chk_ai_model_usage_completion_tokens
        CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
    CONSTRAINT chk_ai_model_usage_cached_prompt_tokens
        CHECK (cached_prompt_tokens IS NULL OR cached_prompt_tokens >= 0),
    CONSTRAINT chk_ai_model_usage_reasoning_tokens
        CHECK (reasoning_tokens IS NULL OR reasoning_tokens >= 0),
    CONSTRAINT chk_ai_model_usage_provider_cost_ticks
        CHECK (provider_cost_ticks IS NULL OR provider_cost_ticks >= 0),
    CONSTRAINT chk_ai_model_usage_metering_fields
        CHECK (
            (metering_basis = 0 AND provider_cost_ticks IS NULL)
            OR (
                metering_basis = 1
                AND prompt_tokens IS NULL
                AND completion_tokens IS NULL
                AND cached_prompt_tokens IS NULL
                AND reasoning_tokens IS NULL
                AND (billing_status <> 1 OR provider_cost_ticks IS NOT NULL)
            )
        ),
    CONSTRAINT chk_ai_model_usage_cached_within_prompt
        CHECK (
            cached_prompt_tokens IS NULL
            OR prompt_tokens IS NULL
            OR cached_prompt_tokens <= prompt_tokens
        ),
    CONSTRAINT chk_ai_model_usage_charged_quota
        CHECK (charged_quota_minor IS NULL OR charged_quota_minor >= 0),
    CONSTRAINT chk_ai_model_usage_finish_reason
        CHECK (
            finish_reason IS NULL
            OR (
                finish_reason = BTRIM(finish_reason)
                AND LENGTH(finish_reason) > 0
            )
        ),
    CONSTRAINT chk_ai_model_usage_failure_code
        CHECK (
            failure_code IS NULL
            OR (
                failure_code = BTRIM(failure_code)
                AND LENGTH(failure_code) > 0
            )
        ),
    CONSTRAINT chk_ai_model_usage_settled_at
        CHECK (settled_at IS NULL OR settled_at >= created_at)
);

-- 用户消费分页以用户等值条件开头，再按创建时间和 Hybrid ID 倒序提供稳定游标。
CREATE INDEX idx_ai_model_usage_login_created_id
    ON ai_model_usage (
        login_identity_id,
        created_at DESC,
        id DESC
    );

-- 用户按模型筛选时同时固定两个等值条件，避免模型列阻断普通用户分页的时间排序。
CREATE INDEX idx_ai_model_usage_login_model_created_id
    ON ai_model_usage (
        login_identity_id,
        ai_model_id,
        created_at DESC,
        id DESC
    );

-- 失败代码仅在异常记录中存在，部分索引避免为正常消费写入大量无意义的 NULL 索引项。
CREATE INDEX idx_ai_model_usage_failure_created_id
    ON ai_model_usage (
        failure_code,
        created_at DESC,
        id DESC
    )
    WHERE failure_code IS NOT NULL;

-- 后台恢复任务只扫描预扣中和待核对记录，部分索引避免低基数状态产生全量普通索引。
CREATE INDEX idx_ai_model_usage_pending_created_id
    ON ai_model_usage (
        billing_status,
        created_at ASC,
        id ASC
    )
    WHERE billing_status IN (0, 3);

COMMENT ON TABLE ai_model_usage IS
    '一次上游模型 HTTP 或 SSE 调用的核心用量与额度结算记录；预扣失败且未调用模型的请求不写入本表';
COMMENT ON COLUMN ai_model_usage.id IS
    'HybridSemaphoreIdWorker 生成的固定 16 字节主键，同时作为后端模型请求 ID';
COMMENT ON COLUMN ai_model_usage.login_identity_id IS
    '逻辑关联 userloginidentity.id 的消费用户 ID，不建立物理外键';
COMMENT ON COLUMN ai_model_usage.ai_model_id IS
    '逻辑关联 ai_model.id 的调用模型 ID；模型名称通过模型表查询，不在消费表重复保存';
COMMENT ON COLUMN ai_model_usage.billing_status IS
    '结算状态：0=RESERVED，1=SETTLED，2=FAILED_REFUNDED，3=RECONCILE_REQUIRED，4=REFUNDED';
COMMENT ON COLUMN ai_model_usage.metering_basis IS
    '计量依据：0=TOKEN，1=PROVIDER_COST_TICKS；历史记录统一为 TOKEN';
COMMENT ON COLUMN ai_model_usage.prompt_tokens IS
    '上游最终 Usage 返回的实际输入 Token；NULL 表示尚未结算或上游未报告';
COMMENT ON COLUMN ai_model_usage.completion_tokens IS
    '上游最终 Usage 返回的实际输出 Token；NULL 表示尚未结算或上游未报告';
COMMENT ON COLUMN ai_model_usage.cached_prompt_tokens IS
    '上游 Prompt Cache 命中的输入 Token，是 prompt_tokens 的子集，与本项目 Redis 无关';
COMMENT ON COLUMN ai_model_usage.reasoning_tokens IS
    '上游报告的思考 Token；供应商已将其包含于 completion_tokens 时不得重复累计';
COMMENT ON COLUMN ai_model_usage.provider_cost_ticks IS
    '供应商返回的精确美元成本 ticks；仅成本计量且证据完整的已结算记录允许非空';
COMMENT ON COLUMN ai_model_usage.charged_quota_minor IS
    '最终实际扣除的内部额度最小单位整数值；NULL 表示尚未完成结算';
COMMENT ON COLUMN ai_model_usage.finish_reason IS
    '模型结束原因，例如 STOP、LENGTH、CLIENT_CANCELLED 或 UPSTREAM_ERROR';
COMMENT ON COLUMN ai_model_usage.failure_code IS
    '可安全对外映射的受控失败代码，禁止保存完整异常堆栈或上游敏感响应';
COMMENT ON COLUMN ai_model_usage.created_at IS
    '预扣成功并创建模型请求记录的时间，不单独保存预扣时间';
COMMENT ON COLUMN ai_model_usage.settled_at IS
    '读取最终 Usage 并完成额度结算的时间';

COMMENT ON INDEX idx_ai_model_usage_login_created_id IS
    '支持按用户和创建时间倒序稳定分页查询全部模型消费记录';
COMMENT ON INDEX idx_ai_model_usage_login_model_created_id IS
    '支持按用户及指定模型和创建时间倒序稳定分页查询消费记录';
COMMENT ON INDEX idx_ai_model_usage_failure_created_id IS
    '支持按受控失败代码和创建时间倒序排查异常模型调用';
COMMENT ON INDEX idx_ai_model_usage_pending_created_id IS
    '支持后台任务按创建时间扫描超时预扣与待核对记录';

COMMIT;
