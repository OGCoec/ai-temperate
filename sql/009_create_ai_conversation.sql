BEGIN;

CREATE TABLE ai_conversation (
    id BYTEA NOT NULL,
    login_identity_id BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_compacted_message_id BIGINT,
    compacted_context JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_ai_conversation
        PRIMARY KEY (id),
    CONSTRAINT chk_ai_conversation_id_length
        CHECK (OCTET_LENGTH(id) = 16),
    CONSTRAINT chk_ai_conversation_login_identity_id
        CHECK (login_identity_id > 0),
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

-- 用户会话列表只读取未软删除的数据，并按照包含创建时间顺序的 Hybrid ID 倒序执行游标分页。
CREATE INDEX idx_ai_conversation_active_user_id
    ON ai_conversation (
        login_identity_id,
        id DESC
    )
    WHERE is_active = TRUE;

COMMENT ON TABLE ai_conversation IS
    '用户与 AI 模型进行连续对话的会话主表；一个用户可以拥有多个会话';
COMMENT ON COLUMN ai_conversation.id IS
    'HybridSemaphoreIdWorker 生成的固定 16 字节会话主键；ID 本身包含创建时间顺序，可用于会话列表倒序分页';
COMMENT ON COLUMN ai_conversation.login_identity_id IS
    '逻辑关联 userloginidentity.id 的用户身份 ID；不建立物理外键，写入前由 Service 校验用户是否存在';
COMMENT ON COLUMN ai_conversation.is_active IS
    '会话软删除状态：TRUE=有效，FALSE=已删除；删除接口只更新该字段，不执行物理 DELETE';
COMMENT ON COLUMN ai_conversation.last_compacted_message_id IS
    '最近一次压缩已经覆盖到的 ai_conversation_message.id；该消息及其之前的消息均已包含在 compacted_context 中，未执行过压缩时为空';
COMMENT ON COLUMN ai_conversation.compacted_context IS
    '截至 last_compacted_message_id 的完整压缩上下文，以 JSONB 保存；未执行过压缩时为空';
COMMENT ON COLUMN ai_conversation.created_at IS
    '会话创建时间，仅用于展示和审计；会话列表的主要排序依据是包含时间信息的 id';

COMMENT ON INDEX idx_ai_conversation_active_user_id IS
    '支持按用户查询有效会话，并按照 16 字节 Hybrid 会话 ID 倒序执行游标分页';

COMMIT;
