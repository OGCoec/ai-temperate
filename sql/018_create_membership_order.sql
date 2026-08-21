BEGIN;

CREATE TABLE membership_order (
    id BYTEA NOT NULL,
    login_identity_id BIGINT NOT NULL,
    membership_tier SMALLINT NOT NULL,
    pay_amount_yuan NUMERIC(12, 2) NOT NULL,
    pay_type VARCHAR(16) NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0,
    idempotency_key UUID NOT NULL,
    provider_trade_no VARCHAR(128),
    payment_started_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    closing_deadline_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    state_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_membership_order
        PRIMARY KEY (id),
    CONSTRAINT uk_membership_order_idempotency_key
        UNIQUE (idempotency_key),
    CONSTRAINT uk_membership_order_provider_trade_no
        UNIQUE (provider_trade_no),
    CONSTRAINT chk_membership_order_id_length
        CHECK (OCTET_LENGTH(id) = 16),
    CONSTRAINT chk_membership_order_tier
        CHECK (membership_tier BETWEEN 1 AND 6),
    CONSTRAINT chk_membership_order_amount
        CHECK (pay_amount_yuan >= 0),
    CONSTRAINT chk_membership_order_state_version
        CHECK (state_version > 0),
    CONSTRAINT chk_membership_order_payment_started_before_expiry
        CHECK (payment_started_at IS NULL OR payment_started_at < expires_at),
    CONSTRAINT chk_membership_order_status
        CHECK (status IN (0, 1, 2, 3, 4)),
    CONSTRAINT chk_membership_order_pay_type
        CHECK (
            pay_type = BTRIM(pay_type)
            AND LENGTH(pay_type) > 0
        )
);

COMMENT ON TABLE membership_order IS
    '会员支付订单主表；保存会员套餐模拟支付订单、支付状态和第三方交易流水，不连接真实六号支付、不保存回调原始报文或退款记录';
COMMENT ON COLUMN membership_order.id IS
    'HybridSemaphoreIdWorker 生成的固定 16 字节订单主键；跨 API、Redis、RabbitMQ 与模拟支付 out_trade_no 时统一编码为 22 字符无填充 Base64URL';
COMMENT ON COLUMN membership_order.login_identity_id IS
    '下单用户的登录身份 ID；逻辑关联 userloginidentity.id，不建立物理外键';
COMMENT ON COLUMN membership_order.membership_tier IS
    '本次购买的目标会员等级：1=GO，2=EDU，3=TEAM，4=PLUS，5=PRO，6=MAX';
COMMENT ON COLUMN membership_order.pay_amount_yuan IS
    '本次订单最终应付人民币金额；Java 使用 BigDecimal 处理，禁止使用 double';
COMMENT ON COLUMN membership_order.pay_type IS
    '支付方式代码，例如 alipay 或 wxpay；允许值由服务端支付方式白名单校验';
COMMENT ON COLUMN membership_order.status IS
    '订单状态：0=PENDING_PAYMENT 待支付，1=CLOSING 正在调用第三方关单，2=PAID 已支付，3=CANCELLED 用户主动取消，4=CLOSED 超时或系统关闭';
COMMENT ON COLUMN membership_order.idempotency_key IS
    '前端创建订单时提交的 UUIDv4 幂等键；唯一约束保证同一幂等键只能创建一笔会员订单';
COMMENT ON COLUMN membership_order.provider_trade_no IS
    '模拟支付入口声明的第三方交易流水号 trade_no；支付成功前允许为空，必须按字符串保存，禁止转换为数字或 Hybrid ID';
COMMENT ON COLUMN membership_order.payment_started_at IS
    '用户在订单有效期内首次发起支付的服务端时间；为空表示尚未发起，迟到成功回调不得据此逆转终态';
COMMENT ON COLUMN membership_order.expires_at IS
    '订单允许支付的截止时间；到期仍未确认支付时进入主动查询和关单流程';
COMMENT ON COLUMN membership_order.closing_deadline_at IS
    '固定等于 expires_at 加五分钟的硬关闭截止时间；不得按 RabbitMQ 实际消费时间向后延长';
COMMENT ON COLUMN membership_order.paid_at IS
    '通过异步回调或主动查询确认支付成功的时间；尚未支付时为空';
COMMENT ON COLUMN membership_order.state_version IS
    'Redis 订单状态机每次有效迁移递增的单调版本；PostgreSQL 只接受更大版本，防止旧批次覆盖新状态';
COMMENT ON COLUMN membership_order.created_at IS
    '会员支付订单在本系统中的创建时间，使用带时区时间保存';
COMMENT ON COLUMN membership_order.updated_at IS
    '订单状态或支付信息最后一次发生变化的时间，由应用代码在更新订单时维护';

CREATE INDEX idx_membership_order_identity_created
    ON membership_order (login_identity_id, created_at DESC);

COMMENT ON CONSTRAINT uk_membership_order_provider_trade_no
    ON membership_order IS
    '保证同一个非空第三方支付交易流水号只能绑定一笔会员订单';
COMMENT ON INDEX idx_membership_order_identity_created IS
    '支持按照用户登录身份查询会员订单，并按照订单创建时间倒序返回';

COMMIT;
