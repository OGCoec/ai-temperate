-- PostgreSQL 的并发建索引不能放入事务块；上线时应独立执行并使用 EXPLAIN (ANALYZE, BUFFERS) 验证侧栏查询。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_conversation_active_user_last_message
    ON ai_conversation (
        login_identity_id,
        last_message_id DESC,
        id DESC
    )
    WHERE is_active = TRUE
      AND last_message_id IS NOT NULL;

COMMENT ON INDEX idx_ai_conversation_active_user_last_message IS
    '支持当前用户按最后完整消息倒序读取有效会话侧栏，并使用复合游标稳定翻页';
