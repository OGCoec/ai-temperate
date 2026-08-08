BEGIN;

ALTER TABLE ai_model_usage
    ADD COLUMN metering_basis SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN provider_cost_ticks BIGINT NULL,
    ADD CONSTRAINT chk_ai_model_usage_metering_basis
        CHECK (metering_basis IN (0, 1)),
    ADD CONSTRAINT chk_ai_model_usage_provider_cost_ticks
        CHECK (provider_cost_ticks IS NULL OR provider_cost_ticks >= 0),
    ADD CONSTRAINT chk_ai_model_usage_metering_fields
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
        );

ALTER TABLE ai_model_usage_detail
    ADD COLUMN metering_basis SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN requested_output_count SMALLINT NULL,
    ALTER COLUMN estimated_prompt_tokens DROP NOT NULL,
    ALTER COLUMN max_output_tokens DROP NOT NULL,
    ALTER COLUMN input_ratio_snapshot DROP NOT NULL,
    ALTER COLUMN cached_input_ratio_snapshot DROP NOT NULL,
    ALTER COLUMN output_ratio_snapshot DROP NOT NULL,
    DROP CONSTRAINT chk_ai_model_usage_detail_estimated_prompt_tokens,
    DROP CONSTRAINT chk_ai_model_usage_detail_max_output_tokens,
    DROP CONSTRAINT chk_ai_model_usage_detail_input_ratio,
    DROP CONSTRAINT chk_ai_model_usage_detail_cached_input_ratio,
    DROP CONSTRAINT chk_ai_model_usage_detail_output_ratio,
    ADD CONSTRAINT chk_ai_model_usage_detail_metering_basis
        CHECK (metering_basis IN (0, 1)),
    ADD CONSTRAINT chk_ai_model_usage_detail_estimated_prompt_tokens
        CHECK (estimated_prompt_tokens IS NULL OR estimated_prompt_tokens >= 0),
    ADD CONSTRAINT chk_ai_model_usage_detail_max_output_tokens
        CHECK (max_output_tokens IS NULL OR max_output_tokens > 0),
    ADD CONSTRAINT chk_ai_model_usage_detail_input_ratio
        CHECK (input_ratio_snapshot IS NULL OR input_ratio_snapshot >= 0),
    ADD CONSTRAINT chk_ai_model_usage_detail_cached_input_ratio
        CHECK (cached_input_ratio_snapshot IS NULL OR cached_input_ratio_snapshot >= 0),
    ADD CONSTRAINT chk_ai_model_usage_detail_output_ratio
        CHECK (output_ratio_snapshot IS NULL OR output_ratio_snapshot >= 0),
    ADD CONSTRAINT chk_ai_model_usage_detail_requested_output_count
        CHECK (requested_output_count IS NULL OR requested_output_count BETWEEN 1 AND 10),
    ADD CONSTRAINT chk_ai_model_usage_detail_metering_fields
        CHECK (
            (
                metering_basis = 0
                AND estimated_prompt_tokens IS NOT NULL
                AND max_output_tokens IS NOT NULL
                AND input_ratio_snapshot IS NOT NULL
                AND cached_input_ratio_snapshot IS NOT NULL
                AND output_ratio_snapshot IS NOT NULL
                AND requested_output_count IS NULL
            )
            OR (
                metering_basis = 1
                AND estimated_prompt_tokens IS NULL
                AND max_output_tokens IS NULL
                AND input_ratio_snapshot IS NULL
                AND cached_input_ratio_snapshot IS NULL
                AND output_ratio_snapshot IS NULL
                AND requested_output_count BETWEEN 1 AND 10
            )
        );

ALTER TABLE ai_conversation_generation_payload
    ADD COLUMN metering_basis SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN provider_cost_ticks BIGINT NULL,
    ADD COLUMN metering_evidence JSONB NULL,
    ADD CONSTRAINT chk_ai_conversation_generation_payload_metering_basis
        CHECK (metering_basis IN (0, 1)),
    ADD CONSTRAINT chk_ai_conversation_generation_payload_provider_cost
        CHECK (provider_cost_ticks IS NULL OR provider_cost_ticks >= 0),
    ADD CONSTRAINT chk_ai_conversation_generation_payload_metering_fields
        CHECK (
            (metering_basis = 0
                AND provider_cost_ticks IS NULL
                AND metering_evidence IS NULL)
            OR (metering_basis = 1
                AND prompt_tokens IS NULL
                AND completion_tokens IS NULL
                AND cached_prompt_tokens IS NULL
                AND reasoning_tokens IS NULL)
        );

COMMENT ON COLUMN ai_model_usage.metering_basis IS
    '计量依据：0=TOKEN，1=PROVIDER_COST_TICKS；迁移前历史记录统一回填为 TOKEN';
COMMENT ON COLUMN ai_model_usage.provider_cost_ticks IS
    '供应商返回的精确美元成本 ticks；仅成本计量且证据完整的已结算记录允许非空';
COMMENT ON COLUMN ai_model_usage_detail.metering_basis IS
    '预扣计量依据：0=TOKEN，1=PROVIDER_COST_TICKS；必须与核心 usage 记录一致';
COMMENT ON COLUMN ai_model_usage_detail.requested_output_count IS
    '成本计量图片请求冻结的输出槽数量；Token 计量记录必须为空';
COMMENT ON COLUMN ai_conversation_generation_payload.metering_basis IS
    '任务创建时冻结的计量依据：0=TOKEN，1=PROVIDER_COST_TICKS';
COMMENT ON COLUMN ai_conversation_generation_payload.provider_cost_ticks IS
    '所有成功图片成本证据完整时汇总的供应商美元成本 ticks；待对账时为空';
COMMENT ON COLUMN ai_conversation_generation_payload.metering_evidence IS
    'schemaVersion=1 的限长安全计量证据，只保存输出序号、状态、安全请求 ID 和十进制成本字符串';

COMMIT;
