-- 本脚本仅供已存在数据库在受控维护窗口中手动执行；CONCURRENTLY 禁止放入事务块。
-- 新索引与应用的 ORDER BY id ASC 一致，创建后需先用 EXPLAIN (ANALYZE, BUFFERS) 验证查询计划。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_conversation_message_conversation_id
    ON ai_conversation_message (conversation_id, id ASC);

-- 旧 idx_ai_conversation_message_conversation_created_id 暂不删除；
-- 第二阶段确认没有其他 created_at 排序查询依赖后，再单独审批并发删除。
