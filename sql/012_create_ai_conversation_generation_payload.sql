BEGIN;

CREATE TABLE IF NOT EXISTS ai_conversation_generation_payload (
    generation_id BYTEA PRIMARY KEY,
    input_text TEXT NOT NULL,
    input_attachments JSONB NOT NULL DEFAULT '[]'::jsonb,
    reasoning_effort SMALLINT NOT NULL,
    assistant_text TEXT NULL,
    assistant_attachments JSONB NULL,
    conversation_message_id BIGINT NULL,
    context_generation VARCHAR(64) NULL,
    ephemeral_ordinal BIGINT NULL,
    prompt_tokens BIGINT NULL,
    completion_tokens BIGINT NULL,
    cached_prompt_tokens BIGINT NULL,
    reasoning_tokens BIGINT NULL,
    model_finish_reason VARCHAR(32) NULL,
    upstream_request_id VARCHAR(255) NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ai_conversation_generation_payload_id_length CHECK (octet_length(generation_id) = 16),
    CONSTRAINT chk_ai_conversation_generation_payload_reasoning CHECK (reasoning_effort BETWEEN 1 AND 5),
    CONSTRAINT chk_ai_conversation_generation_payload_message_id CHECK (
        conversation_message_id IS NULL OR conversation_message_id > 0),
    CONSTRAINT chk_ai_conversation_generation_payload_ephemeral_ordinal CHECK (
        ephemeral_ordinal IS NULL OR ephemeral_ordinal > 0),
    CONSTRAINT chk_ai_conversation_generation_payload_context_cursor CHECK (
        (context_generation IS NULL AND ephemeral_ordinal IS NULL)
        OR (context_generation IS NOT NULL AND ephemeral_ordinal IS NOT NULL)),
    CONSTRAINT chk_ai_conversation_generation_payload_tokens CHECK (
        COALESCE(prompt_tokens, 0) >= 0
        AND COALESCE(completion_tokens, 0) >= 0
        AND COALESCE(cached_prompt_tokens, 0) >= 0
        AND COALESCE(reasoning_tokens, 0) >= 0)
);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_generation_payload_message
    ON ai_conversation_generation_payload (conversation_message_id)
    WHERE conversation_message_id IS NOT NULL;

COMMENT ON TABLE ai_conversation_generation_payload IS
    '异步 AI 生成任务的输入与终态证据表；输入仅写入一次，回答、Usage 和上游信息在取得唯一终态后一次性冻结';
COMMENT ON COLUMN ai_conversation_generation_payload.generation_id IS
    '逻辑关联 ai_conversation_generation.id 的固定 16 字节主键；不建立物理外键';
COMMENT ON COLUMN ai_conversation_generation_payload.input_text IS
    '经过业务校验的用户输入文字；只有附件没有文字时保存空字符串';
COMMENT ON COLUMN ai_conversation_generation_payload.input_attachments IS
    '经过业务校验的输入附件 JSONB 数组；Worker 恢复任务时读取，不在 RabbitMQ 消息中传输正文';
COMMENT ON COLUMN ai_conversation_generation_payload.reasoning_effort IS
    '本次模型生成采用的推理强度代码，合法范围为 1 至 5';
COMMENT ON COLUMN ai_conversation_generation_payload.assistant_text IS
    'Generation 取得唯一终态时冻结的助手回答文字；流式生成过程中不逐片写入本字段';
COMMENT ON COLUMN ai_conversation_generation_payload.assistant_attachments IS
    'Generation 取得唯一终态时冻结的助手附件 JSONB 数组';
COMMENT ON COLUMN ai_conversation_generation_payload.conversation_message_id IS
    '正常持久化回答后对应的 ai_conversation_message.id；失败、取消或尚未落库时为空';
COMMENT ON COLUMN ai_conversation_generation_payload.context_generation IS
    'Redis 临时会话上下文版本；与 ephemeral_ordinal 必须同时为空或同时存在';
COMMENT ON COLUMN ai_conversation_generation_payload.ephemeral_ordinal IS
    '本次生成在 Redis 临时上下文中的顺序游标；与 context_generation 组成上下文提交证据';
COMMENT ON COLUMN ai_conversation_generation_payload.prompt_tokens IS
    '上游最终报告的输入 Token 数；没有最终 Usage 时允许为空';
COMMENT ON COLUMN ai_conversation_generation_payload.completion_tokens IS
    '上游最终报告的输出 Token 数；没有最终 Usage 时允许为空';
COMMENT ON COLUMN ai_conversation_generation_payload.cached_prompt_tokens IS
    '上游 Prompt Cache 命中的输入 Token 数，是 prompt_tokens 的子集';
COMMENT ON COLUMN ai_conversation_generation_payload.reasoning_tokens IS
    '上游报告的推理 Token 数；供应商已经将其包含在 completion_tokens 中时不得重复计费';
COMMENT ON COLUMN ai_conversation_generation_payload.model_finish_reason IS
    '上游模型返回的完成原因；用于正常完成、取消和异常终态的结算证据';
COMMENT ON COLUMN ai_conversation_generation_payload.upstream_request_id IS
    '上游模型厂商返回的请求追踪 ID；上游未提供时允许为空';
COMMENT ON COLUMN ai_conversation_generation_payload.updated_at IS
    'Payload 最后一次更新或冻结终态证据的时间';

COMMENT ON INDEX idx_ai_conversation_generation_payload_message IS
    '支持从完整持久化会话消息反查 Generation Payload，并用于消息逻辑关联孤儿检查';

COMMIT;
