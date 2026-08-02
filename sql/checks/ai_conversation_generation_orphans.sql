-- Generation 关联的用户不存在。
SELECT generation.id, generation.login_identity_id
FROM ai_conversation_generation generation
LEFT JOIN userloginidentity identity
  ON identity.id = generation.login_identity_id
WHERE identity.id IS NULL;

-- Generation 关联的会话不存在。
SELECT generation.id, generation.conversation_id
FROM ai_conversation_generation generation
LEFT JOIN ai_conversation conversation
  ON conversation.id = generation.conversation_id
WHERE generation.conversation_id IS NOT NULL
  AND conversation.id IS NULL;

-- Generation 关联的 Usage 不存在。
SELECT generation.id, generation.usage_id
FROM ai_conversation_generation generation
LEFT JOIN ai_model_usage usage
  ON usage.id = generation.usage_id
WHERE usage.id IS NULL;

-- Generation 关联的模型不存在。
SELECT generation.id, generation.model_id
FROM ai_conversation_generation generation
LEFT JOIN ai_model model
  ON model.id = generation.model_id
WHERE model.id IS NULL;

-- Generation 缺少 Payload，或 Payload 没有对应 Generation。
SELECT generation.id AS generation_id, 'GENERATION_WITHOUT_PAYLOAD' AS orphan_type
FROM ai_conversation_generation generation
LEFT JOIN ai_conversation_generation_payload payload
  ON payload.generation_id = generation.id
WHERE payload.generation_id IS NULL
UNION ALL
SELECT payload.generation_id, 'PAYLOAD_WITHOUT_GENERATION'
FROM ai_conversation_generation_payload payload
LEFT JOIN ai_conversation_generation generation
  ON generation.id = payload.generation_id
WHERE generation.id IS NULL;

-- 已冻结的成功消息 ID 没有对应会话消息。
SELECT payload.generation_id, payload.conversation_message_id
FROM ai_conversation_generation_payload payload
LEFT JOIN ai_conversation_message message
  ON message.id = payload.conversation_message_id
WHERE payload.conversation_message_id IS NOT NULL
  AND message.id IS NULL;
