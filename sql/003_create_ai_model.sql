BEGIN;

CREATE TABLE ai_model (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    model_name VARCHAR(128) NOT NULL,
    model_name_tokens JSONB NOT NULL DEFAULT '[]'::JSONB,
    description TEXT,
    description_tokens JSONB NOT NULL DEFAULT '[]'::JSONB,
    icon VARCHAR(1024),
    tags JSONB NOT NULL DEFAULT '[]'::JSONB,
    vendor VARCHAR(128) NOT NULL,
    input_ratio NUMERIC(20, 8) NOT NULL DEFAULT 1,
    output_ratio NUMERIC(20, 8) NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_ai_model PRIMARY KEY (id),
    CONSTRAINT chk_ai_model_name_not_blank
        CHECK (LENGTH(BTRIM(model_name)) > 0),
    CONSTRAINT chk_ai_model_model_name_tokens_array
        CHECK (JSONB_TYPEOF(model_name_tokens) = 'array'),
    CONSTRAINT chk_ai_model_description_tokens_array
        CHECK (JSONB_TYPEOF(description_tokens) = 'array'),
    CONSTRAINT chk_ai_model_vendor_not_blank
        CHECK (LENGTH(BTRIM(vendor)) > 0),
    CONSTRAINT chk_ai_model_tags_array
        CHECK (JSONB_TYPEOF(tags) = 'array'),
    CONSTRAINT chk_ai_model_input_ratio
        CHECK (input_ratio >= 0),
    CONSTRAINT chk_ai_model_output_ratio
        CHECK (output_ratio >= 0)
);

-- 模型完整名称用于精确查找计费配置，同一模型只允许存在一条记录。
-- PostgreSQL 未显式指定 USING 时默认创建 B-tree；该索引适用于 model_name = 'gpt-5.4-mini' 这类完整名称精确查询。
CREATE UNIQUE INDEX uk_ai_model_model_name
    ON ai_model (model_name);

-- 模型名称的 IK 分词结果由应用层维护；该 JSONB 数组只用于完整词元包含查询，不替代原名称唯一索引。
CREATE INDEX idx_ai_model_model_name_tokens_gin
    ON ai_model
    USING GIN (model_name_tokens);

-- 模型选择框可按名称开头进行不区分大小写的前缀搜索，例如 gpt%、gemini%、claude%。
-- 该索引按 LOWER(model_name) 比较；写入前的 normalize_ai_model_name 触发器负责统一保存格式。
-- varchar_pattern_ops 使非 C 排序规则下的 LOWER(model_name) LIKE 'gpt%' 仍可使用 B-tree 前缀索引。
CREATE INDEX idx_ai_model_model_name_prefix_ci
    ON ai_model (LOWER(model_name) varchar_pattern_ops);

-- 数据库层作为最终兜底：无论写入入口是 Service、脚本还是管理工具，模型名称都统一去除首尾空白并转换为小写。
CREATE OR REPLACE FUNCTION normalize_ai_model_name()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.model_name := LOWER(BTRIM(NEW.model_name));
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ai_model_normalize_model_name
    BEFORE INSERT OR UPDATE OF model_name ON ai_model
    FOR EACH ROW
    EXECUTE FUNCTION normalize_ai_model_name();

-- 模型描述的 IK 分词结果由应用层维护；该 JSONB 数组用于完整词元包含查询，不承担语义理解或任意子串匹配。
CREATE INDEX idx_ai_model_description_tokens_gin
    ON ai_model
    USING GIN (description_tokens);

CREATE OR REPLACE FUNCTION set_ai_model_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ai_model_set_updated_at
    BEFORE UPDATE ON ai_model
    FOR EACH ROW
    EXECUTE FUNCTION set_ai_model_updated_at();

COMMENT ON TABLE ai_model IS 'AI 模型目录及基础 Token 计费倍率';
COMMENT ON COLUMN ai_model.id IS 'BIGINT 自增主键，对外使用 Base64URL 编码';
COMMENT ON COLUMN ai_model.model_name IS '模型唯一名称；写入或修改前由数据库触发器去除首尾空白并转换为小写，按规范化值精确匹配';
COMMENT ON COLUMN ai_model.model_name_tokens IS '模型名称经 Java IK 分词后的 JSONB 字符串数组；由应用层与 model_name 在同一事务内同步维护';
COMMENT ON COLUMN ai_model.description IS '模型原始描述文本；中文分词与全文搜索索引由应用层搜索流程负责维护';
COMMENT ON COLUMN ai_model.description_tokens IS '模型描述经 Java IK 分词后的 JSONB 字符串数组；由应用层与 description 在同一事务内同步维护';
COMMENT ON COLUMN ai_model.icon IS '模型图标 URL 或资源路径';
COMMENT ON COLUMN ai_model.tags IS '模型标签 JSON 数组';
COMMENT ON COLUMN ai_model.vendor IS '模型厂商名称或稳定厂商代码';
COMMENT ON COLUMN ai_model.input_ratio IS '输入 Token 绝对倍率';
COMMENT ON COLUMN ai_model.output_ratio IS '输出 Token 绝对倍率；粗略额度=(输入Token×输入倍率+输出Token×输出倍率)×分组倍率';
COMMENT ON COLUMN ai_model.created_at IS '创建时间';
COMMENT ON COLUMN ai_model.updated_at IS '最后更新时间';

COMMIT;
