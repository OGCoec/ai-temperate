BEGIN;

CREATE TABLE user_api_key_model (
    user_api_key_id BIGINT NOT NULL,
    ai_model_id BIGINT NOT NULL,

    CONSTRAINT pk_user_api_key_model
        PRIMARY KEY (user_api_key_id, ai_model_id)
);

-- 联合主键已经支持根据 API Key 检查和查询模型；
-- 这个索引用于从模型反向查询映射关系和执行孤儿检查。
CREATE INDEX idx_user_api_key_model_ai_model
    ON user_api_key_model (ai_model_id);

COMMENT ON TABLE user_api_key_model IS
    '用户 API Key 与允许调用的 AI 模型映射表；一行表示一个 API Key 被授权调用一个模型';

COMMENT ON COLUMN user_api_key_model.user_api_key_id IS
    '逻辑关联 user_api_key.id，不建立物理外键';

COMMENT ON COLUMN user_api_key_model.ai_model_id IS
    '逻辑关联 ai_model.id，不建立物理外键';

COMMIT;
