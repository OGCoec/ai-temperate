-- 检查无法关联用户登录身份的会话记录；正常结果必须为空集。
SELECT
    conversation.id AS ai_conversation_id,
    conversation.login_identity_id
FROM ai_conversation conversation
LEFT JOIN userloginidentity login_identity
    ON login_identity.id = conversation.login_identity_id
WHERE login_identity.id IS NULL
ORDER BY conversation.id;
