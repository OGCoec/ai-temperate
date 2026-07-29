-- 检查无法关联核心用量记录的详情记录；正常结果必须为空集。
SELECT
    detail.id AS ai_model_usage_detail_id,
    detail.usage_id
FROM ai_model_usage_detail detail
LEFT JOIN ai_model_usage usage
    ON usage.id = detail.usage_id
WHERE usage.id IS NULL
ORDER BY detail.id;

-- 检查缺少一对一详情记录的核心用量记录；正常结果必须为空集。
SELECT
    usage.id AS ai_model_usage_id,
    usage.created_at
FROM ai_model_usage usage
LEFT JOIN ai_model_usage_detail detail
    ON detail.usage_id = usage.id
WHERE detail.id IS NULL
ORDER BY usage.created_at, usage.id;
