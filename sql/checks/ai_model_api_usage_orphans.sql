-- 检查缺少 API Key 或 AI 模型主记录的核心用量；正常结果必须为空集。
SELECT
    usage.id AS ai_model_api_usage_id,
    usage.ai_model_id,
    usage.created_at,
    (api_key.id IS NULL) AS missing_user_api_key,
    (model.id IS NULL) AS missing_ai_model
FROM ai_model_api_usage usage
LEFT JOIN user_api_key api_key
    ON api_key.key_digest = usage.key_digest
LEFT JOIN ai_model model
    ON model.id = usage.ai_model_id
WHERE api_key.id IS NULL
   OR model.id IS NULL;

