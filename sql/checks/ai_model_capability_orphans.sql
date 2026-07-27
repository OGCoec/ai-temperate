-- 返回无法关联到 ai_model 的能力记录；正常结果必须为空集。
SELECT
    capability.id,
    capability.ai_model_id,
    capability.capability_code
FROM ai_model_capability capability
LEFT JOIN ai_model model ON model.id = capability.ai_model_id
WHERE model.id IS NULL
ORDER BY capability.id;
