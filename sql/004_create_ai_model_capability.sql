BEGIN;

CREATE TABLE ai_model_capability (
    id BIGINT NOT NULL,
    ai_model_id BIGINT NOT NULL,
    capability_code VARCHAR(64) NOT NULL,
    created_at DATE NOT NULL DEFAULT CURRENT_DATE,
    updated_at DATE NOT NULL DEFAULT CURRENT_DATE,

    CONSTRAINT pk_ai_model_capability PRIMARY KEY (id),
    CONSTRAINT uk_ai_model_capability_model_code
        UNIQUE (ai_model_id, capability_code),
    CONSTRAINT chk_ai_model_capability_code
        CHECK (capability_code IN (
            'CHAT_COMPLETIONS',
            'RESPONSES',
            'IMAGE',
            'VIDEO',
            'AUDIO'
        ))
);

-- 项目不建立物理外键；普通索引用于按模型批量加载能力和执行孤儿数据核查。
CREATE INDEX idx_ai_model_capability_ai_model_id
    ON ai_model_capability (ai_model_id);

-- 能力代码位于最左列以加速按能力反查模型，模型 ID 位于第二列用于逻辑关联并稳定同一能力下的索引顺序。
CREATE INDEX idx_ai_model_capability_code_model_id
    ON ai_model_capability (
        capability_code ASC,
        ai_model_id ASC
    );

CREATE OR REPLACE FUNCTION set_ai_model_capability_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_DATE;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ai_model_capability_set_updated_at
    BEFORE UPDATE ON ai_model_capability
    FOR EACH ROW
    EXECUTE FUNCTION set_ai_model_capability_updated_at();

COMMENT ON TABLE ai_model_capability IS 'AI 模型支持的 API 能力大类明细表，通过 ai_model_id 与 ai_model 进行一对多逻辑关联';
COMMENT ON COLUMN ai_model_capability.id IS '应用层共享 SnowflakeIdWorker 生成的 BIGINT 主键，仅用于能力明细内部标识';
COMMENT ON COLUMN ai_model_capability.ai_model_id IS '逻辑关联 ai_model.id，不建立物理外键';
COMMENT ON COLUMN ai_model_capability.capability_code IS '固定能力大类代码：CHAT_COMPLETIONS、RESPONSES、IMAGE、VIDEO、AUDIO';
COMMENT ON COLUMN ai_model_capability.created_at IS '能力记录创建日期';
COMMENT ON COLUMN ai_model_capability.updated_at IS '能力记录最后更新日期';

COMMIT;
