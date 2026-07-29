-- 检查无法关联会话主记录的消息记录；正常结果必须为空集。
SELECT
    message.id AS ai_conversation_message_id,
    message.conversation_id
FROM ai_conversation_message message
LEFT JOIN ai_conversation conversation
    ON conversation.id = message.conversation_id
WHERE conversation.id IS NULL
ORDER BY message.id;
