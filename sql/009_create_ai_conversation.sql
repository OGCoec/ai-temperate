BEGIN;

CREATE TABLE ai_conversation (
    id BYTEA NOT NULL,
    login_identity_id BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    title VARCHAR(80),
    last_message_id BIGINT,
    last_compacted_message_id BIGINT,
    compacted_context JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_ai_conversation
        PRIMARY KEY (id),
    CONSTRAINT chk_ai_conversation_id_length
        CHECK (OCTET_LENGTH(id) = 16),
    CONSTRAINT chk_ai_conversation_login_identity_id
        CHECK (login_identity_id > 0),
    CONSTRAINT chk_ai_conversation_title
        CHECK (title IS NULL OR LENGTH(BTRIM(title)) > 0),
    CONSTRAINT chk_ai_conversation_last_message_id
        CHECK (last_message_id IS NULL OR last_message_id > 0),
    CONSTRAINT chk_ai_conversation_last_compacted_message_id
        CHECK (
            last_compacted_message_id IS NULL
            OR last_compacted_message_id > 0
        ),
    CONSTRAINT chk_ai_conversation_compaction_pair
        CHECK (
            (
                last_compacted_message_id IS NULL
                AND compacted_context IS NULL
            )
            OR
            (
                last_compacted_message_id IS NOT NULL
                AND compacted_context IS NOT NULL
            )
        )
);

-- 侧栏只读取已有完整消息的有效会话，并按最后消息和会话 ID 稳定倒序分页。
CREATE INDEX idx_ai_conversation_active_user_last_message
    ON ai_conversation (
        login_identity_id,
        last_message_id DESC,
        id DESC
    )
    WHERE is_active = TRUE
      AND last_message_id IS NOT NULL;

COMMENT ON TABLE ai_conversation IS
    '用户与 AI 模型连续对话的会话主表；只保存可恢复的持久状态，不保存 Redis 中断草稿';
COMMENT ON COLUMN ai_conversation.id IS
    'HybridSemaphoreIdWorker 生成的固定 16 字节会话主键；对外统一编码为 22 位 Base64URL 公共 ID';
COMMENT ON COLUMN ai_conversation.login_identity_id IS
    '逻辑关联 userloginidentity.id 的用户身份 ID；不建立物理外键，写入前由 Service 校验归属';
COMMENT ON COLUMN ai_conversation.is_active IS
    '会话软删除状态：TRUE=有效，FALSE=已删除；删除接口不得执行物理 DELETE';
COMMENT ON COLUMN ai_conversation.title IS
    '首条成功持久化消息的文字标题快照；首条消息只有附件时保持为空且后续不自动补写';
COMMENT ON COLUMN ai_conversation.last_message_id IS
    '本会话最后一条完整持久化消息 ID；侧栏排序和游标分页以该值为权威依据';
COMMENT ON COLUMN ai_conversation.last_compacted_message_id IS
    '最近一次持久上下文压缩已覆盖到的 ai_conversation_message.id；未压缩时为空';
COMMENT ON COLUMN ai_conversation.compacted_context IS
    '截至 last_compacted_message_id 的持久上下文 JSONB 摘要；未压缩时为空';
COMMENT ON COLUMN ai_conversation.created_at IS
    '会话创建时间，仅用于展示和审计；侧栏活跃顺序以 last_message_id 为准';

COMMENT ON INDEX idx_ai_conversation_active_user_last_message IS
    '支持当前用户按最后完整消息倒序读取有效会话侧栏，并使用复合游标稳定翻页';

COMMIT;
