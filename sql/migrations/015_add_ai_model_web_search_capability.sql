BEGIN;

-- 仅扩展模型能力白名单，不修改已有模型能力记录，也不把 RESPONSES 自动等同为联网搜索。
ALTER TABLE ai_model_capability
    DROP CONSTRAINT IF EXISTS chk_ai_model_capability_code;

ALTER TABLE ai_model_capability
    ADD CONSTRAINT chk_ai_model_capability_code
        CHECK (capability_code IN (
            'CHAT_COMPLETIONS',
            'RESPONSES',
            'WEB_SEARCH',
            'IMAGE',
            'VIDEO',
            'AUDIO'
        ));

COMMENT ON COLUMN ai_model_capability.capability_code IS
    '固定能力大类代码：CHAT_COMPLETIONS、RESPONSES、WEB_SEARCH、IMAGE、VIDEO、AUDIO';

COMMIT;
