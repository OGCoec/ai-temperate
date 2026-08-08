BEGIN;

CREATE TABLE IF NOT EXISTS ai_conversation_generation (
    id BYTEA PRIMARY KEY,
    login_identity_id BIGINT NOT NULL,
    conversation_id BYTEA NULL,
    usage_id BYTEA NOT NULL,
    idempotency_key_digest BYTEA NOT NULL,
    model_id BIGINT NOT NULL,
    generation_status SMALLINT NOT NULL,
    observer_status SMALLINT NOT NULL,
    observer_epoch BIGINT NOT NULL DEFAULT 0,
    owner_instance_id VARCHAR(128) NULL,
    cancel_source VARCHAR(32) NULL,
    terminal_type VARCHAR(32) NULL,
    terminal_reason VARCHAR(64) NULL,
	terminal_version INTEGER NOT NULL DEFAULT 0,
	video_stage VARCHAR(48) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ NULL,
    detached_at TIMESTAMPTZ NULL,
    cancel_requested_at TIMESTAMPTZ NULL,
    terminal_at TIMESTAMPTZ NULL,
    settled_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ai_conversation_generation_id_length CHECK (octet_length(id) = 16),
    CONSTRAINT chk_ai_conversation_generation_conversation_id_length CHECK (
        conversation_id IS NULL OR octet_length(conversation_id) = 16),
    CONSTRAINT chk_ai_conversation_generation_usage_id_length CHECK (octet_length(usage_id) = 16),
    CONSTRAINT chk_ai_conversation_generation_digest_length CHECK (octet_length(idempotency_key_digest) = 32),
    CONSTRAINT chk_ai_conversation_generation_status CHECK (generation_status BETWEEN 0 AND 6),
    CONSTRAINT chk_ai_conversation_generation_observer_status CHECK (observer_status BETWEEN 0 AND 1),
    CONSTRAINT chk_ai_conversation_generation_observer_epoch CHECK (observer_epoch >= 0),
	CONSTRAINT chk_ai_conversation_generation_terminal_version CHECK (terminal_version >= 0),
	CONSTRAINT chk_ai_conversation_generation_video_stage CHECK (
		video_stage IS NULL OR video_stage IN (
			'QUEUED', 'VALIDATING_MEDIA', 'RESERVED', 'XAI_SUBMITTING',
			'XAI_PENDING', 'XAI_DONE', 'OSS_TRANSFERRING', 'OSS_READY',
			'SUCCEEDED', 'MEDIA_VALIDATION_FAILED', 'XAI_REJECTED',
			'XAI_FAILED', 'XAI_EXPIRED', 'XAI_RESULT_UNCERTAIN',
			'OSS_TRANSFER_FAILED', 'BILLING_RECONCILE_REQUIRED')),
    CONSTRAINT chk_ai_conversation_generation_cancel_source CHECK (
        cancel_source IS NULL OR cancel_source IN (
            'USER_STOP', 'ADMIN_CANCEL', 'CLIENT_EXIT_TIMEOUT')),
    CONSTRAINT chk_ai_conversation_generation_terminal_type CHECK (
        terminal_type IS NULL OR terminal_type IN (
            'COMPLETED', 'CLIENT_CANCELLED', 'ADMIN_CANCELLED',
            'UPSTREAM_FAILED', 'SYSTEM_FAILED')),
    CONSTRAINT uq_ai_conversation_generation_usage UNIQUE (usage_id),
    CONSTRAINT uq_ai_conversation_generation_idempotency UNIQUE (idempotency_key_digest)
);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_generation_recovery
    ON ai_conversation_generation (generation_status, updated_at, id);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_generation_owner
    ON ai_conversation_generation (owner_instance_id, generation_status, id);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_generation_user_active
    ON ai_conversation_generation (login_identity_id, generation_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_generation_detached_due
    ON ai_conversation_generation (detached_at, id)
    WHERE observer_status = 1
      AND generation_status IN (0, 1);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_generation_conversation
    ON ai_conversation_generation (conversation_id, created_at DESC)
    WHERE conversation_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_conversation_generation_conversation_active
    ON ai_conversation_generation (conversation_id)
    WHERE conversation_id IS NOT NULL
      AND generation_status IN (0, 1, 2, 3);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_generation_model
    ON ai_conversation_generation (model_id, created_at DESC);

COMMENT ON TABLE ai_conversation_generation IS
    '异步 AI 回答生成任务控制表；保存任务状态、SSE 观察状态、Worker 所有权、取消来源以及唯一终态，不保存逐片流式输出';
COMMENT ON COLUMN ai_conversation_generation.id IS
    '固定 16 字节生成任务主键；对外编码为 22 字符无填充 Base64URL Generation ID';
COMMENT ON COLUMN ai_conversation_generation.login_identity_id IS
    '发起生成任务的用户登录身份 ID；逻辑关联 userloginidentity.id，不建立物理外键';
COMMENT ON COLUMN ai_conversation_generation.conversation_id IS
    '任务所属会话的固定 16 字节 ID；逻辑关联 ai_conversation.id，新会话尚未确定时允许为空';
COMMENT ON COLUMN ai_conversation_generation.usage_id IS
    '对应预扣和最终结算记录的固定 16 字节 ID；逻辑关联 ai_model_usage.id，并通过唯一约束保证一对一关系';
COMMENT ON COLUMN ai_conversation_generation.idempotency_key_digest IS
    '用户身份与原始幂等键经过 HMAC-SHA256 计算得到的 32 字节摘要；禁止保存原始幂等键';
COMMENT ON COLUMN ai_conversation_generation.model_id IS
    '本次生成使用的模型内部 ID；逻辑关联 ai_model.id，不建立物理外键';
COMMENT ON COLUMN ai_conversation_generation.generation_status IS
    '任务状态：0=QUEUED，1=RUNNING，2=CANCEL_REQUESTED，3=TERMINAL_PENDING_BILLING，4=SETTLED，5=REFUNDED，6=RECONCILE_REQUIRED';
COMMENT ON COLUMN ai_conversation_generation.observer_status IS
    'SSE 观察者状态：0=ATTACHED，1=DETACHED；观察者断开本身不代表取消或退款';
COMMENT ON COLUMN ai_conversation_generation.observer_epoch IS
    'SSE 观察连接版本号；每次重新连接递增，防止旧连接结束回调覆盖新连接状态';
COMMENT ON COLUMN ai_conversation_generation.owner_instance_id IS
    '当前领取并持有模型生成任务的应用实例 ID，用于定向发送取消命令';
COMMENT ON COLUMN ai_conversation_generation.cancel_source IS
    '首次成功取消来源：USER_STOP、ADMIN_CANCEL 或 CLIENT_EXIT_TIMEOUT；后续取消不得覆盖';
COMMENT ON COLUMN ai_conversation_generation.terminal_type IS
    '唯一业务终态：COMPLETED、CLIENT_CANCELLED、ADMIN_CANCELLED、UPSTREAM_FAILED 或 SYSTEM_FAILED';
COMMENT ON COLUMN ai_conversation_generation.terminal_reason IS
    '受控终态原因代码；禁止保存第三方异常原文、正文、Token 或其他敏感内容';
COMMENT ON COLUMN ai_conversation_generation.terminal_version IS
	'终态版本号；通过预期版本更新保证多个终态竞争时只有一个终态取得所有权';
COMMENT ON COLUMN ai_conversation_generation.video_stage IS
	'视频任务跨 xAI 与 OSS 的安全阶段；普通对话和图片任务为空，不保存临时 URL 或授权信息';
COMMENT ON COLUMN ai_conversation_generation.created_at IS
    'Generation、Payload 和 Usage 预扣事务创建成功的时间';
COMMENT ON COLUMN ai_conversation_generation.started_at IS
    'Generation Worker 成功领取任务并准备调用上游模型的时间';
COMMENT ON COLUMN ai_conversation_generation.detached_at IS
    '当前 observer_epoch 对应的 SSE 观察者开始失联时间；重新连接时清空';
COMMENT ON COLUMN ai_conversation_generation.cancel_requested_at IS
    '首次成功持久化取消意图的时间';
COMMENT ON COLUMN ai_conversation_generation.terminal_at IS
    'Worker 或取消流程成功冻结唯一业务终态的时间';
COMMENT ON COLUMN ai_conversation_generation.settled_at IS
    'Billing Consumer 完成资金、Usage、Detail 和 Generation 状态事务的时间';
COMMENT ON COLUMN ai_conversation_generation.updated_at IS
    'Generation 状态最后更新时间，同时用于有界恢复和终态清理';

COMMENT ON CONSTRAINT uq_ai_conversation_generation_usage
    ON ai_conversation_generation IS
    '保证一条模型 Usage 记录只能绑定一个 Generation，并提供 usage_id 精确查询索引';
COMMENT ON CONSTRAINT uq_ai_conversation_generation_idempotency
    ON ai_conversation_generation IS
    '保证同一安全幂等摘要只能创建一个 Generation，防止重复预扣和重复调用模型';

COMMENT ON INDEX idx_ai_conversation_generation_recovery IS
    '支持分钟级异常恢复任务按状态和更新时间批量查找待发布、超时 Worker 和待结算 Generation';
COMMENT ON INDEX idx_ai_conversation_generation_owner IS
    '支持按照 Worker owner 实例和任务状态定位活动 Generation，并定向处理取消命令';
COMMENT ON INDEX idx_ai_conversation_generation_user_active IS
    '支持按照用户和任务状态查询有界数量的活动 Generation，用于前端恢复多个后台生成任务';
COMMENT ON INDEX idx_ai_conversation_generation_detached_due IS
    '支持分钟级兜底查找已经失联且仍处于排队或运行状态的 Generation；实时 30 秒检查由 RabbitMQ 延迟消息负责';
COMMENT ON INDEX idx_ai_conversation_generation_conversation IS
    '支持按会话读取 Generation 记录，并为 conversation_id 逻辑关联和删除前引用检查提供索引';
COMMENT ON INDEX uq_ai_conversation_generation_conversation_active IS
    '保证同一会话最多存在一个排队、运行、取消处理中或等待计费的活动 Generation';
COMMENT ON INDEX idx_ai_conversation_generation_model IS
    '支持按模型查询 Generation，并为 model_id 逻辑关联和模型删除前引用检查提供索引';

COMMIT;
