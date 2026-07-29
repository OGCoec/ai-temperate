-- 检查缺少对应登录身份的模型用量记录；正常结果必须为空集。
SELECT
    usage.id AS ai_model_usage_id,
    usage.login_identity_id
FROM ai_model_usage usage
LEFT JOIN userloginidentity identity
    ON identity.id = usage.login_identity_id
WHERE identity.id IS NULL
ORDER BY usage.created_at, usage.id;

-- 检查缺少对应模型的模型用量记录；正常结果必须为空集。
SELECT
    usage.id AS ai_model_usage_id,
    usage.ai_model_id
FROM ai_model_usage usage
LEFT JOIN ai_model model
    ON model.id = usage.ai_model_id
WHERE model.id IS NULL
ORDER BY usage.created_at, usage.id;
