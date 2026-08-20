BEGIN;

CREATE TABLE membership_payment_callback (
    id BYTEA NOT NULL,
    order_id BYTEA NOT NULL,
    provider_trade_no VARCHAR(128) NOT NULL,
    trade_status VARCHAR(32) NOT NULL,
    paid_amount_yuan NUMERIC(12, 2) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_membership_payment_callback
        PRIMARY KEY (id),
    CONSTRAINT uk_membership_payment_callback_trade_status
        UNIQUE (provider_trade_no, trade_status),
    CONSTRAINT chk_membership_payment_callback_id_length
        CHECK (OCTET_LENGTH(id) = 16),
    CONSTRAINT chk_membership_payment_callback_order_id_length
        CHECK (OCTET_LENGTH(order_id) = 16),
    CONSTRAINT chk_membership_payment_callback_amount
        CHECK (paid_amount_yuan >= 0),
    CONSTRAINT chk_membership_payment_callback_trade_status
        CHECK (
            trade_status = BTRIM(trade_status)
            AND LENGTH(trade_status) > 0
        )
);

COMMENT ON TABLE membership_payment_callback IS
    '会员支付回调记录表；只保存已经通过签名、商户号、订单号和金额校验的第三方支付回调，用于支付确认审计和重复回调幂等处理';
COMMENT ON COLUMN membership_payment_callback.id IS
    'HybridSemaphoreIdWorker 生成的固定 16 字节支付回调记录主键';
COMMENT ON COLUMN membership_payment_callback.order_id IS
    '对应 membership_order.id 的固定 16 字节 Hybrid 订单 ID；逻辑关联会员订单，不建立物理外键';
COMMENT ON COLUMN membership_payment_callback.provider_trade_no IS
    '六号支付返回的第三方交易流水号 trade_no；必须与会员订单中记录的第三方流水号一致';
COMMENT ON COLUMN membership_payment_callback.trade_status IS
    '第三方支付回调携带的交易状态原始代码；不得使用该字段绕过回调验签、商户号和金额校验';
COMMENT ON COLUMN membership_payment_callback.paid_amount_yuan IS
    '支付回调声明的人民币实付金额；处理回调时必须使用 BigDecimal 与订单应付金额精确比较';
COMMENT ON COLUMN membership_payment_callback.received_at IS
    '支付回调通过安全校验并进入本系统数据库事务的时间';

COMMENT ON CONSTRAINT uk_membership_payment_callback_trade_status
    ON membership_payment_callback IS
    '同一第三方交易流水号和交易状态只记录一次，用于重复回调的数据库最终幂等保障';

CREATE INDEX idx_membership_payment_callback_order
    ON membership_payment_callback (order_id, received_at DESC);

COMMENT ON INDEX idx_membership_payment_callback_order IS
    '支持根据会员订单查询已经接收并通过安全校验的支付回调记录';

COMMIT;
