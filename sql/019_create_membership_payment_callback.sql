BEGIN;

CREATE TABLE membership_payment_callback (
    id BYTEA NOT NULL,
    order_id BYTEA NOT NULL,
    provider_trade_no VARCHAR(128) NOT NULL,
    trade_status VARCHAR(32) NOT NULL,
    paid_amount_yuan NUMERIC(12, 2) NOT NULL,
    paid_at TIMESTAMPTZ(6) NOT NULL,
    received_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolution VARCHAR(32),
    resolved_at TIMESTAMPTZ(6),

    CONSTRAINT pk_membership_payment_callback
        PRIMARY KEY (id),
    CONSTRAINT uk_membership_payment_callback_order_id
        UNIQUE (order_id),
    CONSTRAINT uk_membership_payment_callback_provider_trade_no
        UNIQUE (provider_trade_no),
    CONSTRAINT chk_membership_payment_callback_id_length
        CHECK (OCTET_LENGTH(id) = 16),
    CONSTRAINT chk_membership_payment_callback_order_id_length
        CHECK (OCTET_LENGTH(order_id) = 16),
    CONSTRAINT chk_membership_payment_callback_amount
        CHECK (paid_amount_yuan >= 0),
    CONSTRAINT chk_membership_payment_callback_resolution
        CHECK (
            resolution IS NULL
            OR resolution IN (
                'APPLIED',
                'ALREADY_APPLIED',
                'REFUND_REQUIRED',
                'REJECTED'
            )
        ),
    CONSTRAINT chk_membership_payment_callback_resolution_time
        CHECK (
            (resolution IS NULL AND resolved_at IS NULL)
            OR (resolution IS NOT NULL AND resolved_at IS NOT NULL)
        ),
    CONSTRAINT chk_membership_payment_callback_trade_status
        CHECK (
            trade_status = BTRIM(trade_status)
            AND LENGTH(trade_status) > 0
        )
);

COMMENT ON TABLE membership_payment_callback IS
    '会员支付回调记录表；保存通过模拟入口密钥、商户号、格式和支持状态校验的首次回调事实；订单金额、支付方式或时间不匹配时保留 REJECTED 审计，不表示完成真实 RSA 验签';
COMMENT ON COLUMN membership_payment_callback.id IS
    'HybridSemaphoreIdWorker 生成的固定 16 字节支付回调记录主键；跨应用边界时统一编码为 22 字符无填充 Base64URL';
COMMENT ON COLUMN membership_payment_callback.order_id IS
    '对应 membership_order.id 的固定 16 字节 Hybrid 订单 ID；对外统一编码为 22 字符无填充 Base64URL，逻辑关联会员订单且不建立物理外键';
COMMENT ON COLUMN membership_payment_callback.provider_trade_no IS
    '模拟支付入口声明的第三方交易流水号 trade_no；最终绑定会员订单时必须与订单记录的第三方流水号一致';
COMMENT ON COLUMN membership_payment_callback.trade_status IS
    '第三方支付回调携带的交易状态原始代码；不得使用该字段绕过回调验签、商户号和金额校验';
COMMENT ON COLUMN membership_payment_callback.paid_amount_yuan IS
    '支付回调声明的人民币实付金额；处理回调时必须使用 BigDecimal 与订单应付金额精确比较';
COMMENT ON COLUMN membership_payment_callback.paid_at IS
    '第三方回调声明的支付完成时间；必须晚于支付发起时间且不得晚于服务端接收时间';
COMMENT ON COLUMN membership_payment_callback.received_at IS
    '支付回调通过安全校验并进入本系统数据库事务的时间';
COMMENT ON COLUMN membership_payment_callback.resolution IS
    '回调最终裁决：APPLIED、ALREADY_APPLIED、REFUND_REQUIRED 或 REJECTED；尚未处理时为空';
COMMENT ON COLUMN membership_payment_callback.resolved_at IS
    '回调完成最终裁决的服务端时间；必须与 resolution 同时为空或同时非空';

COMMENT ON CONSTRAINT uk_membership_payment_callback_order_id
    ON membership_payment_callback IS
    '同一会员订单只允许保存一条支付回调，用于按商户订单号提供数据库最终幂等保障';

COMMENT ON CONSTRAINT uk_membership_payment_callback_provider_trade_no
    ON membership_payment_callback IS
    '同一第三方交易流水号只允许保存一次，防止交易流水重复绑定或跨订单复用';

COMMIT;
