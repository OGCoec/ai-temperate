BEGIN;

CREATE TABLE user_api_key_model (
    user_api_key_id BIGINT NOT NULL,
    ai_model_id BIGINT NOT NULL,
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT pk_user_api_key_model
        PRIMARY KEY (user_api_key_id, ai_model_id),
    CONSTRAINT chk_user_api_key_model_status
        CHECK (status IN (0, 1)),
    CONSTRAINT chk_user_api_key_model_deleted_state
        CHECK (
            (status = 0 AND deleted_at IS NOT NULL)
            OR (status = 1 AND deleted_at IS NULL)
        )
);

-- 联合主键已经支持根据 API Key 检查和查询模型；
-- 这个索引用于从模型反向查询映射关系和执行孤儿检查。
CREATE INDEX idx_user_api_key_model_ai_model
    ON user_api_key_model (ai_model_id, user_api_key_id)
    WHERE status = 1;

CREATE OR REPLACE FUNCTION set_user_api_key_model_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_api_key_model_set_updated_at
    BEFORE UPDATE ON user_api_key_model
    FOR EACH ROW
    EXECUTE FUNCTION set_user_api_key_model_updated_at();

COMMENT ON TABLE user_api_key_model IS
    '用户 API Key 与允许调用的 AI 模型映射表；一行表示一个 API Key 被授权调用一个模型';

COMMENT ON COLUMN user_api_key_model.user_api_key_id IS
    '逻辑关联 user_api_key.id，不建立物理外键';

COMMENT ON COLUMN user_api_key_model.ai_model_id IS
    '逻辑关联 ai_model.id，不建立物理外键';

COMMENT ON COLUMN user_api_key_model.status IS
    '授权状态：0=REVOKED，1=ACTIVE；已撤销映射保留原联合主键以支持后续 UPSERT 恢复';

COMMENT ON COLUMN user_api_key_model.created_at IS
    '该 API Key 首次获得当前模型授权的时间';

COMMENT ON COLUMN user_api_key_model.updated_at IS
    '映射授权或撤销状态最后一次发生变化的时间';

COMMENT ON COLUMN user_api_key_model.deleted_at IS
    '软撤销时间；仅当 status=0 时非空，恢复授权时必须清空';

COMMENT ON INDEX idx_user_api_key_model_ai_model IS
    '支持从启用模型反向定位仍然有效的 API Key 授权，并为模型方向孤儿检查提供索引';

COMMIT;
