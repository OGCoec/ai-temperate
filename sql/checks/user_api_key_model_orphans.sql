-- 检查缺少 API Key 或 AI 模型记录的授权映射；结果集中的每一行都是待处置的孤儿记录。
SELECT
    uakm.user_api_key_id,
    uakm.ai_model_id,
    (uak.id IS NULL) AS missing_user_api_key,
    (am.id IS NULL) AS missing_ai_model
FROM user_api_key_model uakm
LEFT JOIN user_api_key uak
    ON uak.id = uakm.user_api_key_id
LEFT JOIN ai_model am
    ON am.id = uakm.ai_model_id
WHERE uak.id IS NULL
   OR am.id IS NULL;
