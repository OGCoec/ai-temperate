-- 检查无法关联用户登录身份的会话记录；正常结果必须为空集。
SELECT
    conversation.id AS ai_conversation_id,
    conversation.login_identity_id
FROM ai_conversation conversation
LEFT JOIN userloginidentity login_identity
    ON login_identity.id = conversation.login_identity_id
WHERE login_identity.id IS NULL
ORDER BY conversation.id;

-- 检查 last_message_id 指向不存在消息或其他会话消息的记录；正常结果必须为空集。
SELECT
    conversation.id AS ai_conversation_id,
    conversation.last_message_id,
    message.conversation_id AS actual_message_conversation_id
FROM ai_conversation conversation
LEFT JOIN ai_conversation_message message
    ON message.id = conversation.last_message_id
WHERE conversation.last_message_id IS NOT NULL
  AND (
      message.id IS NULL
      OR message.conversation_id <> conversation.id
  )
ORDER BY conversation.id;
