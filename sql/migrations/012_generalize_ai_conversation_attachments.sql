BEGIN;

ALTER TABLE ai_conversation
    ADD COLUMN title VARCHAR(80),
    ADD COLUMN last_message_id BIGINT;

ALTER TABLE ai_conversation
    ADD CONSTRAINT chk_ai_conversation_title
        CHECK (title IS NULL OR LENGTH(BTRIM(title)) > 0),
    ADD CONSTRAINT chk_ai_conversation_last_message_id
        CHECK (last_message_id IS NULL OR last_message_id > 0);

COMMENT ON COLUMN ai_conversation.title IS
    '首条成功持久化消息的文字标题快照；首条消息只有附件时保持为空且后续不自动补写';
COMMENT ON COLUMN ai_conversation.last_message_id IS
    '本会话最后一条完整持久化消息 ID；侧栏排序和游标分页以该值为权威依据';

ALTER TABLE ai_conversation_message
    RENAME COLUMN content_photos TO content_attachments;

ALTER TABLE ai_conversation_message
    RENAME COLUMN question_photos TO response_attachments;

ALTER TABLE ai_conversation_message
    ALTER COLUMN content_text DROP NOT NULL,
    ALTER COLUMN question_tokens DROP NOT NULL;

ALTER TABLE ai_conversation_message
    DROP CONSTRAINT chk_ai_conversation_message_content_photos_array,
    DROP CONSTRAINT chk_ai_conversation_message_question_photos_array,
    DROP CONSTRAINT chk_ai_conversation_message_content_text,
    DROP CONSTRAINT chk_ai_conversation_message_question_tokens;

ALTER TABLE ai_conversation_message
    ADD CONSTRAINT chk_ai_conversation_message_content_attachments_array
        CHECK (JSONB_TYPEOF(content_attachments) = 'array'),
    ADD CONSTRAINT chk_ai_conversation_message_response_attachments_array
        CHECK (JSONB_TYPEOF(response_attachments) = 'array'),
    ADD CONSTRAINT chk_ai_conversation_message_user_content
        CHECK (
            LENGTH(BTRIM(COALESCE(content_text, ''))) > 0
            OR JSONB_ARRAY_LENGTH(content_attachments) > 0
        ),
    ADD CONSTRAINT chk_ai_conversation_message_assistant_content
        CHECK (
            LENGTH(BTRIM(COALESCE(question_tokens, ''))) > 0
            OR JSONB_ARRAY_LENGTH(response_attachments) > 0
        );

COMMENT ON COLUMN ai_conversation_message.content_attachments IS
    '用户输入的通用附件对象 JSONB 数组，可包含任意文件类型的完整公网 URL 或存储失败占位；不建立索引';
COMMENT ON COLUMN ai_conversation_message.response_attachments IS
    '模型生成并持久化到 OSS 的通用附件对象 JSONB 数组，允许保存存储失败占位；不建立索引';

COMMIT;
