BEGIN;

CREATE TABLE ai_model (
    id BIGINT NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    model_name_tokens JSONB NOT NULL DEFAULT '[]'::JSONB,
    description TEXT,
    description_tokens JSONB NOT NULL DEFAULT '[]'::JSONB,
    icon_id BIGINT,
    tags JSONB NOT NULL DEFAULT '[]'::JSONB,
    vendor VARCHAR(128) NOT NULL,
    input_ratio NUMERIC(20, 8) NOT NULL DEFAULT 1,
    cached_input_ratio NUMERIC(20, 8) NOT NULL DEFAULT 1,
    output_ratio NUMERIC(20, 8) NOT NULL DEFAULT 1,
    context_window_tokens BIGINT,
    max_output_tokens BIGINT,
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    row_version BIGINT NOT NULL DEFAULT 1,
    created_at DATE NOT NULL DEFAULT CURRENT_DATE,
    updated_at DATE NOT NULL DEFAULT CURRENT_DATE,

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
    CONSTRAINT chk_ai_model_cached_input_ratio
        CHECK (cached_input_ratio >= 0),
    CONSTRAINT chk_ai_model_output_ratio
        CHECK (output_ratio >= 0),
    CONSTRAINT chk_ai_model_token_limits_configured_together
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
    CONSTRAINT chk_ai_model_context_window_tokens
        CHECK (
            context_window_tokens IS NULL
            OR (
                context_window_tokens > 0
                AND context_window_tokens <= 2147483647000
                AND MOD(context_window_tokens, 1000) = 0
            )
        ),
    CONSTRAINT chk_ai_model_max_output_tokens
        CHECK (
            max_output_tokens IS NULL
            OR (
                max_output_tokens > 0
                AND max_output_tokens <= 2147483647000
                AND MOD(max_output_tokens, 1000) = 0
            )
        ),
    CONSTRAINT chk_ai_model_output_within_context_window
        CHECK (
            max_output_tokens IS NULL
            OR max_output_tokens <= context_window_tokens
        ),
    CONSTRAINT chk_ai_model_row_version
        CHECK (row_version > 0)
);

-- 模型完整名称用于精确查找计费配置，同一模型只允许存在一条记录。
-- PostgreSQL 未显式指定 USING 时默认创建 B-tree；该索引适用于 model_name = 'gpt-5.4-mini' 这类完整名称精确查询。
CREATE UNIQUE INDEX uk_ai_model_model_name
    ON ai_model (model_name);

-- 图标资源是可选逻辑关联；部分索引只覆盖实际选择图标的模型，供关联查询和删除前引用检查使用。
CREATE INDEX idx_ai_model_icon_id
    ON ai_model (icon_id)
    WHERE icon_id IS NOT NULL;

-- 该联合索引直接服务于先按输入倍率、再按输出倍率、最后按模型名称升序展示的列表查询。
CREATE INDEX idx_ai_model_input_output_name
    ON ai_model (input_ratio ASC, output_ratio ASC, model_name ASC);

-- 该联合索引直接服务于先按输出倍率、再按输入倍率、最后按模型名称升序展示的列表查询。
CREATE INDEX idx_ai_model_output_input_name
    ON ai_model (output_ratio ASC, input_ratio ASC, model_name ASC);

-- 状态等值筛选后按输入倍率优先排序，字段顺序与受控 PageHelper 排序表达式保持一致。
CREATE INDEX idx_ai_model_enabled_input_output_name
    ON ai_model (is_enabled, input_ratio ASC, output_ratio ASC, model_name ASC);

-- 状态等值筛选后按输出倍率优先排序，避免依赖客户端动态 SQL 或额外 DESC 索引。
CREATE INDEX idx_ai_model_enabled_output_input_name
    ON ai_model (is_enabled, output_ratio ASC, input_ratio ASC, model_name ASC);

-- 管理列表先展示已启用模型，再展示已停用模型；同一开关状态内按模型名称升序排列。
CREATE INDEX idx_ai_model_enabled_name
    ON ai_model (is_enabled DESC, model_name ASC);

-- 加密快照只读取启用模型；部分索引避免为低基数布尔字段建立无效的全量普通索引。
CREATE INDEX idx_ai_model_enabled_id
    ON ai_model (id)
    WHERE is_enabled = TRUE;

-- 模型名称按 ASCII 横杠切分的结果由应用层维护；该 JSONB 数组只用于完整词元包含查询，不替代原名称唯一索引。
CREATE INDEX idx_ai_model_model_name_tokens_gin
    ON ai_model
    USING GIN (model_name_tokens);

-- 旧版模型选择框曾按名称开头搜索；本次完整词元搜索不再使用该路径，但保留索引以便应用版本直接回滚。
-- 该兼容索引按 LOWER(model_name) 比较；写入前的 normalize_ai_model_name 触发器负责统一保存格式。
-- varchar_pattern_ops 保留旧版本在非 C 排序规则下执行 LOWER(model_name) LIKE 'gpt%' 的索引能力。
CREATE INDEX idx_ai_model_model_name_prefix_ci
    ON ai_model (LOWER(model_name) varchar_pattern_ops);

-- 厂商精确查询使用 LOWER(vendor) 等值表达式；varchar_pattern_ops B-tree 同时保留前缀运算兼容能力。
CREATE INDEX idx_ai_model_vendor_prefix_ci
    ON ai_model (LOWER(vendor) varchar_pattern_ops);

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
    NEW.updated_at = CURRENT_DATE;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ai_model_set_updated_at
    BEFORE UPDATE ON ai_model
    FOR EACH ROW
    EXECUTE FUNCTION set_ai_model_updated_at();

COMMENT ON TABLE ai_model IS 'AI 模型目录及基础 Token 计费倍率';
COMMENT ON COLUMN ai_model.id IS '应用层共享 SnowflakeIdWorker 生成的 BIGINT 主键，对外使用 Base64URL 编码';
COMMENT ON COLUMN ai_model.model_name IS '模型唯一名称；写入或修改前由数据库触发器去除首尾空白并转换为小写，按规范化值精确匹配';
COMMENT ON COLUMN ai_model.model_name_tokens IS '模型名称经 Java 按 ASCII 横杠切分后的 JSONB 字符串数组；由应用层与 model_name 在同一事务内同步维护';
COMMENT ON COLUMN ai_model.description IS '模型原始描述文本；中文分词与全文搜索索引由应用层搜索流程负责维护';
COMMENT ON COLUMN ai_model.description_tokens IS '模型描述经 Java IK 分词后的 JSONB 字符串数组；由应用层与 description 在同一事务内同步维护';
COMMENT ON COLUMN ai_model.icon_id IS '可选图标资源内部 ID，逻辑关联 ai_model_icon.id，不建立物理外键';
COMMENT ON COLUMN ai_model.tags IS '模型标签 JSON 数组';
COMMENT ON COLUMN ai_model.vendor IS '模型厂商名称或稳定厂商代码';
COMMENT ON COLUMN ai_model.input_ratio IS
    '未命中上游 Prompt Cache 的输入 Token 绝对计费倍率';
COMMENT ON COLUMN ai_model.cached_input_ratio IS
    '上游模型 Prompt Cache 命中输入 Token 的绝对计费倍率，与本项目 Redis 缓存无关';
COMMENT ON COLUMN ai_model.output_ratio IS
    '输出 Token 绝对倍率；粗略额度=(未缓存输入Token×输入倍率+缓存命中Token×缓存输入倍率+输出Token×输出倍率)×分组倍率';
COMMENT ON COLUMN ai_model.context_window_tokens IS
    '模型单次上游请求允许容纳的最大总上下文 Token 数，用于本地组装历史消息、当前输入和预留输出后的容量校验；NULL 表示尚未配置，不得据此发起模型调用';
COMMENT ON COLUMN ai_model.max_output_tokens IS
    '模型单次生成请求允许累计产生的最大输出 Token 数；流式调用时表示整条 SSE 响应从开始到结束的累计输出上限，而不是单个 SSE 分片的上限；NULL 表示尚未配置';
COMMENT ON COLUMN ai_model.is_enabled IS '模型总开关；TRUE=启用，FALSE=禁用，默认禁用用于防止漏传配置时意外上线';
COMMENT ON COLUMN ai_model.row_version IS '模型字段和启停状态的乐观锁版本；每次受控修改原子递增';
COMMENT ON COLUMN ai_model.created_at IS '创建日期';
COMMENT ON COLUMN ai_model.updated_at IS '最后更新日期';

COMMIT;
