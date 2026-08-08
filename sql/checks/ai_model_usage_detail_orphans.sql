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

-- 检查用量详情中指向不存在会话的逻辑关联；正常结果必须为空集。
SELECT
    detail.id AS ai_model_usage_detail_id,
    detail.conversation_id
FROM ai_model_usage_detail detail
LEFT JOIN ai_conversation conversation
    ON conversation.id = detail.conversation_id
WHERE conversation.id IS NULL
ORDER BY detail.id;

-- 检查已完成请求中指向不存在消息的逻辑关联；正常结果必须为空集。
SELECT
    detail.id AS ai_model_usage_detail_id,
    detail.conversation_message_id
FROM ai_model_usage_detail detail
LEFT JOIN ai_conversation_message message
    ON message.id = detail.conversation_message_id
WHERE detail.conversation_message_id IS NOT NULL
  AND message.id IS NULL
ORDER BY detail.id;

-- 检查用量详情关联的消息是否属于同一个会话；正常结果必须为空集。
SELECT
    detail.id AS ai_model_usage_detail_id,
    detail.conversation_id AS usage_conversation_id,
    message.conversation_id AS message_conversation_id,
    detail.conversation_message_id
FROM ai_model_usage_detail detail
INNER JOIN ai_conversation_message message
    ON message.id = detail.conversation_message_id
WHERE detail.conversation_message_id IS NOT NULL
  AND message.conversation_id <> detail.conversation_id
ORDER BY detail.id;

-- 检查核心用量、详情与预扣字段的计量依据不一致；正常结果必须为空集。
SELECT
    detail.id AS ai_model_usage_detail_id,
    usage.metering_basis AS usage_metering_basis,
    detail.metering_basis AS detail_metering_basis,
    video.usage_id AS video_usage_id
FROM ai_model_usage_detail detail
INNER JOIN ai_model_usage usage
    ON usage.id = detail.usage_id
LEFT JOIN ai_model_usage_video_detail video
    ON video.usage_id = detail.usage_id
WHERE usage.metering_basis <> detail.metering_basis
   OR (detail.metering_basis = 0
       AND (
           detail.requested_output_count IS NOT NULL
           OR video.usage_id IS NOT NULL
       ))
   OR (detail.metering_basis = 1
       AND (
           detail.estimated_prompt_tokens IS NOT NULL
           OR detail.max_output_tokens IS NOT NULL
           OR detail.input_ratio_snapshot IS NOT NULL
           OR detail.cached_input_ratio_snapshot IS NOT NULL
           OR detail.output_ratio_snapshot IS NOT NULL
           OR (
               detail.requested_output_count IS NULL
               AND video.usage_id IS NULL
           )
           OR (
               detail.requested_output_count IS NOT NULL
               AND video.usage_id IS NOT NULL
           )
       ))
ORDER BY detail.id;

-- 检查无法关联通用用量详情的视频计费快照；正常结果必须为空集。
SELECT
    video.usage_id
FROM ai_model_usage_video_detail video
LEFT JOIN ai_model_usage_detail detail
    ON detail.usage_id = video.usage_id
WHERE detail.usage_id IS NULL
ORDER BY video.usage_id;
