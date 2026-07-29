BEGIN;

CREATE TABLE ai_conversation_message (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    conversation_id BYTEA NOT NULL,
    content_text TEXT NOT NULL,
    content_parts JSONB NOT NULL DEFAULT '[]'::JSONB,
    question_tokens TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_ai_conversation_message
        PRIMARY KEY (id),
    CONSTRAINT chk_ai_conversation_message_conversation_id_length
        CHECK (OCTET_LENGTH(conversation_id) = 16),
    CONSTRAINT chk_ai_conversation_message_content_parts_array
        CHECK (JSONB_TYPEOF(content_parts) = 'array'),
    CONSTRAINT chk_ai_conversation_message_content_text
        CHECK (LENGTH(BTRIM(content_text)) > 0),
    CONSTRAINT chk_ai_conversation_message_question_tokens
        CHECK (LENGTH(BTRIM(question_tokens)) > 0)
);

-- created_at 提供消息时间顺序，id 在同一时间值下提供稳定的第二排序条件。
CREATE INDEX idx_ai_conversation_message_conversation_created_id
    ON ai_conversation_message (
        conversation_id,
        created_at ASC,
        id ASC
    );

-- 用户原始提问由 Java IK 分词后写入 JSONB 字符串数组，GIN 倒排索引服务于完整词元包含查询。
CREATE INDEX idx_ai_conversation_message_user_content_parts_gin
    ON ai_conversation_message
    USING GIN (content_parts)
    WHERE content_parts <> '[]'::JSONB;

COMMENT ON TABLE ai_conversation_message IS
    'AI 会话消息表；每行保存一次用户提问、该提问的 IK 分词结果以及对应的模型回答';
COMMENT ON COLUMN ai_conversation_message.id IS
    '消息表自身的 PostgreSQL BIGINT 自增主键，用于唯一定位消息并辅助稳定排序';
COMMENT ON COLUMN ai_conversation_message.conversation_id IS
    '逻辑关联 ai_conversation.id 的固定 16 字节会话 ID；不建立物理外键，写入前由 Service 校验会话存在、有效且属于当前用户';
COMMENT ON COLUMN ai_conversation_message.content_text IS
    '未分词的用户原始提问；文字提问保存原文，图片提问保存图片上传至对象存储后生成的 URL；本字段不建立搜索索引';
COMMENT ON COLUMN ai_conversation_message.content_parts IS
    '文字提问经过 Java IK 分词后的 JSONB 字符串数组并由 GIN 索引；图片 URL 不参与分词，图片提问保存空数组';
COMMENT ON COLUMN ai_conversation_message.question_tokens IS
    '模型针对本行用户提问返回的原始回答文本；保留为 TEXT，且不建立搜索索引';
COMMENT ON COLUMN ai_conversation_message.created_at IS
    '消息创建时间；同一会话按照 created_at 和自增 id 升序恢复消息顺序';

COMMENT ON INDEX idx_ai_conversation_message_conversation_created_id IS
    '支持根据会话 ID 按创建时间及消息 ID 稳定升序读取完整对话记录';
COMMENT ON INDEX idx_ai_conversation_message_user_content_parts_gin IS
    '支持使用 JSONB 包含或顶层元素存在运算符检索用户提问的完整 IK 词元；GIN 不提供词元顺序匹配能力';

COMMIT;
