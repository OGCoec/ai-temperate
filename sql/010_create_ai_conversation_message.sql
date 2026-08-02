BEGIN;

CREATE TABLE ai_conversation_message (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    conversation_id BYTEA NOT NULL,
    content_text TEXT,
    content_attachments JSONB NOT NULL DEFAULT '[]'::JSONB,
    content_parts JSONB NOT NULL DEFAULT '[]'::JSONB,
    question_tokens TEXT,
    response_attachments JSONB NOT NULL DEFAULT '[]'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_ai_conversation_message
        PRIMARY KEY (id),
    CONSTRAINT chk_ai_conversation_message_conversation_id_length
        CHECK (OCTET_LENGTH(conversation_id) = 16),
    CONSTRAINT chk_ai_conversation_message_content_attachments_array
        CHECK (JSONB_TYPEOF(content_attachments) = 'array'),
    CONSTRAINT chk_ai_conversation_message_content_parts_array
        CHECK (JSONB_TYPEOF(content_parts) = 'array'),
    CONSTRAINT chk_ai_conversation_message_response_attachments_array
        CHECK (JSONB_TYPEOF(response_attachments) = 'array'),
    CONSTRAINT chk_ai_conversation_message_user_content
        CHECK (
            LENGTH(BTRIM(COALESCE(content_text, ''))) > 0
            OR JSONB_ARRAY_LENGTH(content_attachments) > 0
        ),
    CONSTRAINT chk_ai_conversation_message_assistant_content
        CHECK (
            LENGTH(BTRIM(COALESCE(question_tokens, ''))) > 0
            OR JSONB_ARRAY_LENGTH(response_attachments) > 0
        )
);

-- 自增 id 是同一会话内的权威消息顺序，避免时钟精度或时钟差影响恢复顺序。
CREATE INDEX idx_ai_conversation_message_conversation_id
    ON ai_conversation_message (
        conversation_id,
        id ASC
    );

-- 用户原始提问由 Java IK 分词后写入 JSONB 字符串数组，GIN 倒排索引只服务完整词元包含查询。
CREATE INDEX idx_ai_conversation_message_user_content_parts_gin
    ON ai_conversation_message
    USING GIN (content_parts)
    WHERE content_parts <> '[]'::JSONB;

COMMENT ON TABLE ai_conversation_message IS
    'AI 会话完整消息表；每行保存一次用户输入、一次最终助手响应及双方通用附件，流式中断草稿不进入本表';
COMMENT ON COLUMN ai_conversation_message.id IS
    'PostgreSQL BIGINT 自增消息主键；可预取序列值后显式插入，以便先生成最终 OSS 对象路径';
COMMENT ON COLUMN ai_conversation_message.conversation_id IS
    '逻辑关联 ai_conversation.id 的固定 16 字节会话 ID；不建立物理外键，写入前由 Service 校验归属';
COMMENT ON COLUMN ai_conversation_message.content_text IS
    '未分词的用户原始文字输入；允许为空，但与 content_attachments 不能同时为空';
COMMENT ON COLUMN ai_conversation_message.content_attachments IS
    '用户输入的通用附件对象 JSONB 数组，可包含任意文件类型的完整公网 URL 或存储失败占位；不建立索引';
COMMENT ON COLUMN ai_conversation_message.content_parts IS
    'content_text 经 Java IK 分词后的 JSONB 字符串数组并由 GIN 索引；附件名称和 URL 不参与分词';
COMMENT ON COLUMN ai_conversation_message.question_tokens IS
    '模型针对本轮用户输入返回的最终文字；允许为空，但与 response_attachments 不能同时为空';
COMMENT ON COLUMN ai_conversation_message.response_attachments IS
    '模型生成并持久化到 OSS 的通用附件对象 JSONB 数组，允许保存存储失败占位；不建立索引';
COMMENT ON COLUMN ai_conversation_message.created_at IS
    '完整消息事务提交时的创建时间；同一会话的恢复顺序以自增 id 升序为准';

COMMENT ON INDEX idx_ai_conversation_message_conversation_id IS
    '支持根据会话 ID 按自增消息 ID 稳定升序或反向游标分页读取完整对话记录';
COMMENT ON INDEX idx_ai_conversation_message_user_content_parts_gin IS
    '支持使用 JSONB 包含或顶层元素存在运算符检索用户提问的完整 IK 词元';

COMMIT;
