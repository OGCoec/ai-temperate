BEGIN;

CREATE TABLE ai_model_icon (
    id BIGINT NOT NULL,
    icon_name VARCHAR(128) NOT NULL,
    icon_url VARCHAR(1024) NOT NULL,
    object_key VARCHAR(1024),
    description VARCHAR(512),
    created_at DATE NOT NULL DEFAULT CURRENT_DATE,
    updated_at DATE NOT NULL DEFAULT CURRENT_DATE,

    CONSTRAINT pk_ai_model_icon PRIMARY KEY (id),
    CONSTRAINT uk_ai_model_icon_object_key UNIQUE (object_key),
    CONSTRAINT chk_ai_model_icon_name_not_blank
        CHECK (icon_name = BTRIM(icon_name) AND LENGTH(icon_name) > 0),
    CONSTRAINT chk_ai_model_icon_url_https
        CHECK (icon_url = BTRIM(icon_url) AND icon_url ~* '^https://'),
    CONSTRAINT chk_ai_model_icon_object_key_not_blank
        CHECK (
            object_key IS NULL
            OR (
                object_key = BTRIM(object_key)
                AND LENGTH(object_key) > 0
                AND object_key !~ '(^/|\\|\.\.)'
            )
        ),
    CONSTRAINT chk_ai_model_icon_description_not_blank
        CHECK (description IS NULL OR LENGTH(BTRIM(description)) > 0)
);

CREATE UNIQUE INDEX uk_ai_model_icon_name_ci
    ON ai_model_icon (LOWER(icon_name));

CREATE OR REPLACE FUNCTION set_ai_model_icon_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_DATE;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ai_model_icon_set_updated_at
    BEFORE UPDATE ON ai_model_icon
    FOR EACH ROW
    EXECUTE FUNCTION set_ai_model_icon_updated_at();

COMMENT ON TABLE ai_model_icon IS 'AI 模型可复用图标资源库；本地图片上传 OSS 后与已验证的外部图片统一保存最终 HTTPS 地址';
COMMENT ON COLUMN ai_model_icon.id IS '应用层共享 SnowflakeIdWorker 生成的 BIGINT 图标资源内部主键，对外使用 Base64URL 编码';
COMMENT ON COLUMN ai_model_icon.icon_name IS '图标资源唯一名称，例如 OpenAI、Gemini 或 Anthropic';
COMMENT ON COLUMN ai_model_icon.icon_url IS '经应用层验证可读取真实图片内容的最终 HTTPS 地址；可以来自 OSS 或外部站点';
COMMENT ON COLUMN ai_model_icon.object_key IS '应用托管的 OSS Object Key；外部 HTTPS 图片为 NULL，本地上传图片保存精确对象路径';
COMMENT ON COLUMN ai_model_icon.description IS '图标适用厂商或模型系列的可选说明';
COMMENT ON COLUMN ai_model_icon.created_at IS '图标资源创建日期';
COMMENT ON COLUMN ai_model_icon.updated_at IS '图标名称、地址或描述最后修改日期';
COMMENT ON CONSTRAINT pk_ai_model_icon ON ai_model_icon IS '保证每条图标资源具有唯一内部 ID';
COMMENT ON CONSTRAINT uk_ai_model_icon_object_key ON ai_model_icon IS '防止两个图标资源共同管理同一个非空 OSS 对象路径';
COMMENT ON CONSTRAINT chk_ai_model_icon_name_not_blank ON ai_model_icon IS '图标名称必须去除首尾空白且不能为空';
COMMENT ON CONSTRAINT chk_ai_model_icon_url_https ON ai_model_icon IS '图标地址必须去除首尾空白并使用 HTTPS；真实图片校验由应用层负责';
COMMENT ON CONSTRAINT chk_ai_model_icon_object_key_not_blank ON ai_model_icon IS '非空 OSS Object Key 必须是已去除首尾空白的安全相对路径';
COMMENT ON CONSTRAINT chk_ai_model_icon_description_not_blank ON ai_model_icon IS '图标描述为空时使用 NULL，禁止只保存空白字符';
COMMENT ON INDEX uk_ai_model_icon_name_ci IS '以大小写不敏感方式保证图标资源名称唯一';

COMMIT;
