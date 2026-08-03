BEGIN;

-- 先移除旧白名单，才能在同一事务内把旧能力转换为新的输入能力。
ALTER TABLE ai_model_capability
    DROP CONSTRAINT IF EXISTS chk_ai_model_capability_code;

-- 新旧输入能力同时存在时保留新行，避免转换触发模型与能力的唯一约束冲突。
DELETE FROM ai_model_capability AS legacy
USING ai_model_capability AS replacement
WHERE legacy.ai_model_id = replacement.ai_model_id
  AND (
      (legacy.capability_code = 'IMAGE'
          AND replacement.capability_code = 'IMAGE_INPUT')
      OR
      (legacy.capability_code = 'AUDIO'
          AND replacement.capability_code = 'AUDIO_INPUT')
      OR
      (legacy.capability_code = 'VIDEO'
          AND replacement.capability_code = 'VIDEO_INPUT')
  );

-- 旧能力在现有业务中只授权附件输入，因此迁移不得扩大为生成或编辑权限。
UPDATE ai_model_capability
SET capability_code = CASE capability_code
    WHEN 'IMAGE' THEN 'IMAGE_INPUT'
    WHEN 'AUDIO' THEN 'AUDIO_INPUT'
    WHEN 'VIDEO' THEN 'VIDEO_INPUT'
    ELSE capability_code
END
WHERE capability_code IN ('IMAGE', 'AUDIO', 'VIDEO');

ALTER TABLE ai_model_capability
    ADD CONSTRAINT chk_ai_model_capability_code
        CHECK (capability_code IN (
            'CHAT_COMPLETIONS',
            'RESPONSES',
            'WEB_SEARCH',
            'IMAGE_INPUT',
            'IMAGE_GENERATION',
            'IMAGE_EDIT',
            'AUDIO_INPUT',
            'AUDIO_GENERATION',
            'AUDIO_EDIT',
            'VIDEO_INPUT',
            'VIDEO_GENERATION',
            'VIDEO_EDIT'
        ));

COMMENT ON COLUMN ai_model_capability.capability_code IS
    '固定能力代码：CHAT_COMPLETIONS、RESPONSES、WEB_SEARCH，以及图像、音频、视频的输入、生成和编辑能力';

COMMENT ON TABLE ai_model_capability IS
    'AI 模型协议、工具和媒体能力明细表，通过 ai_model_id 与 ai_model 进行一对多逻辑关联';

COMMIT;
