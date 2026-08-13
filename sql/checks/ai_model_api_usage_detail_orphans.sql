-- 检查缺少核心用量主记录的预扣详情；正常结果必须为空集。
SELECT
    detail.id AS ai_model_api_usage_detail_id,
    detail.usage_id,
    TRUE AS missing_usage
FROM ai_model_api_usage_detail detail
LEFT JOIN ai_model_api_usage usage
    ON usage.id = detail.usage_id
WHERE usage.id IS NULL;

-- 检查缺少一对一预扣详情的核心用量；正常结果必须为空集。
SELECT
    usage.id AS ai_model_api_usage_id,
    usage.billing_status,
    usage.created_at,
    TRUE AS missing_detail
FROM ai_model_api_usage usage
LEFT JOIN ai_model_api_usage_detail detail
    ON detail.usage_id = usage.id
WHERE detail.id IS NULL;

