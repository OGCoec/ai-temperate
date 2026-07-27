-- 返回引用了不存在图标资源的 AI 模型；正常结果必须为空集。
SELECT
    model.id,
    model.model_name,
    model.icon_id
FROM ai_model model
LEFT JOIN ai_model_icon icon ON icon.id = model.icon_id
WHERE model.icon_id IS NOT NULL
  AND icon.id IS NULL
ORDER BY model.id;
