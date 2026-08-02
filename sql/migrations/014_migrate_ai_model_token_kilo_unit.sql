BEGIN;

-- 早期已发布环境没有容量列；补为双 NULL 后保留历史模型，但不会把缺失配置伪造为有效额度。
ALTER TABLE ai_model
    ADD COLUMN IF NOT EXISTS context_window_tokens BIGINT,
    ADD COLUMN IF NOT EXISTS max_output_tokens BIGINT;

-- 迁移前必须确认旧记录仍满足二进制 K 约束，避免除法截断悄然改变模型额度。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM ai_model
        WHERE (context_window_tokens IS NULL) <> (max_output_tokens IS NULL)
           OR (
               context_window_tokens IS NOT NULL
               AND (
                   context_window_tokens <= 0
                   OR max_output_tokens <= 0
                   OR context_window_tokens % 1024 <> 0
                   OR max_output_tokens % 1024 <> 0
                   OR max_output_tokens > context_window_tokens
               )
           )
    ) THEN
        RAISE EXCEPTION
            'ai_model token limits are not valid legacy 1024-token K values';
    END IF;
END
$$;

ALTER TABLE ai_model
    DROP CONSTRAINT IF EXISTS chk_ai_model_token_limits_configured_together,
    DROP CONSTRAINT IF EXISTS chk_ai_model_context_window_tokens,
    DROP CONSTRAINT IF EXISTS chk_ai_model_max_output_tokens,
    DROP CONSTRAINT IF EXISTS chk_ai_model_output_within_context_window;

-- 原始值除以 1024 后再乘以 1000，保留管理员此前配置的 K 数值而切换为官方十进制 Token 口径。
UPDATE ai_model
SET context_window_tokens = context_window_tokens / 1024 * 1000,
    max_output_tokens = max_output_tokens / 1024 * 1000,
    row_version = row_version + 1,
    updated_at = CURRENT_DATE
WHERE context_window_tokens IS NOT NULL;

-- 容量尚未配置的旧启用模型不能进入 Fail Closed 的运行时快照，需由管理员补齐限制后再显式启用。
UPDATE ai_model
SET is_enabled = FALSE,
    row_version = row_version + 1,
    updated_at = CURRENT_DATE
WHERE is_enabled = TRUE
  AND context_window_tokens IS NULL;

ALTER TABLE ai_model
    ADD CONSTRAINT chk_ai_model_token_limits_configured_together
        CHECK (
            (
                context_window_tokens IS NULL
                AND max_output_tokens IS NULL
            )
            OR (
                context_window_tokens IS NOT NULL
                AND max_output_tokens IS NOT NULL
            )
        ),
    ADD CONSTRAINT chk_ai_model_context_window_tokens
        CHECK (
            context_window_tokens IS NULL
            OR (
                context_window_tokens > 0
                AND context_window_tokens <= 2147483647000
                AND MOD(context_window_tokens, 1000) = 0
            )
        ),
    ADD CONSTRAINT chk_ai_model_max_output_tokens
        CHECK (
            max_output_tokens IS NULL
            OR (
                max_output_tokens > 0
                AND max_output_tokens <= 2147483647000
                AND MOD(max_output_tokens, 1000) = 0
            )
        ),
    ADD CONSTRAINT chk_ai_model_output_within_context_window
        CHECK (
            max_output_tokens IS NULL
            OR max_output_tokens <= context_window_tokens
        );

COMMIT;
