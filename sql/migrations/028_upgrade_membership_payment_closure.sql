BEGIN;

-- 为已创建的会员订单表补齐支付发起、软关单和版本控制字段；该迁移只做向前兼容的加法。
ALTER TABLE membership_order
    ADD COLUMN IF NOT EXISTS payment_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS closing_deadline_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 1;

-- 约束使用名称探测，避免重复执行迁移时中断部署事务。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'membership_order'::regclass
          AND conname = 'chk_membership_order_state_version'
    ) THEN
        ALTER TABLE membership_order
            ADD CONSTRAINT chk_membership_order_state_version
            CHECK (state_version > 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'membership_order'::regclass
          AND conname = 'chk_membership_order_payment_started_before_expiry'
    ) THEN
        ALTER TABLE membership_order
            ADD CONSTRAINT chk_membership_order_payment_started_before_expiry
            CHECK (payment_started_at IS NULL OR payment_started_at < expires_at);
    END IF;
END
$$;

-- 旧回调记录没有支付完成时间时，以服务端接收时间回填，随后再提升为非空字段。
ALTER TABLE membership_payment_callback
    ADD COLUMN IF NOT EXISTS paid_at TIMESTAMPTZ;

UPDATE membership_payment_callback
SET paid_at = received_at
WHERE paid_at IS NULL;

ALTER TABLE membership_payment_callback
    ALTER COLUMN paid_at SET NOT NULL,
    ADD COLUMN IF NOT EXISTS resolution VARCHAR(32),
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;

-- 旧复合唯一键允许同一订单保存多条事实，必须先移除，再由订单和第三方流水两个独立唯一键共同裁决。
ALTER TABLE membership_payment_callback
    DROP CONSTRAINT IF EXISTS uk_membership_payment_callback_trade_status;

DROP INDEX IF EXISTS idx_membership_payment_callback_order;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'membership_order'::regclass
          AND conname = 'uk_membership_order_provider_trade_no'
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_class
        WHERE relnamespace = 'public'::regnamespace
          AND relname = 'uk_membership_order_provider_trade_no'
          AND relkind = 'i'
    ) THEN
        ALTER TABLE membership_order
            ADD CONSTRAINT uk_membership_order_provider_trade_no
            UNIQUE (provider_trade_no);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'membership_payment_callback'::regclass
          AND conname = 'uk_membership_payment_callback_order_id'
    ) THEN
        ALTER TABLE membership_payment_callback
            ADD CONSTRAINT uk_membership_payment_callback_order_id
            UNIQUE (order_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'membership_payment_callback'::regclass
          AND conname = 'uk_membership_payment_callback_provider_trade_no'
    ) THEN
        ALTER TABLE membership_payment_callback
            ADD CONSTRAINT uk_membership_payment_callback_provider_trade_no
            UNIQUE (provider_trade_no);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'membership_payment_callback'::regclass
          AND conname = 'chk_membership_payment_callback_resolution'
    ) THEN
        ALTER TABLE membership_payment_callback
            ADD CONSTRAINT chk_membership_payment_callback_resolution
            CHECK (resolution IS NULL OR resolution IN (
                'APPLIED', 'ALREADY_APPLIED', 'REFUND_REQUIRED', 'REJECTED'
            ));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'membership_payment_callback'::regclass
          AND conname = 'chk_membership_payment_callback_resolution_time'
    ) THEN
        ALTER TABLE membership_payment_callback
            ADD CONSTRAINT chk_membership_payment_callback_resolution_time
            CHECK ((resolution IS NULL AND resolved_at IS NULL)
                OR (resolution IS NOT NULL AND resolved_at IS NOT NULL));
    END IF;
END
$$;

COMMENT ON COLUMN membership_order.payment_started_at IS
    '用户在订单有效期内首次发起支付的服务端时间；为空表示尚未发起';
COMMENT ON COLUMN membership_order.closing_deadline_at IS
    '订单进入 CLOSING 后允许迟到支付结果收敛的软关闭截止时间';
COMMENT ON COLUMN membership_order.state_version IS
    '订单状态单调递增版本；数据库批量更新只接受更高版本';
COMMENT ON COLUMN membership_payment_callback.paid_at IS
    '第三方回调声明的支付完成时间；必须经过支付发起和硬截止校验';
COMMENT ON COLUMN membership_payment_callback.resolution IS
    '回调最终裁决：APPLIED、ALREADY_APPLIED、REFUND_REQUIRED 或 REJECTED';
COMMENT ON COLUMN membership_payment_callback.resolved_at IS
    '回调完成最终裁决的服务端时间；必须与 resolution 同时为空或同时非空';
COMMENT ON TABLE membership_payment_callback IS
    '会员支付回调记录表；保存通过模拟入口鉴权与格式校验的首次回调事实；订单金额、支付方式或时间不匹配时保留 REJECTED 审计，不表示完成真实 RSA 验签';
COMMENT ON COLUMN membership_payment_callback.provider_trade_no IS
    '模拟支付入口声明的第三方交易流水号 trade_no；最终绑定会员订单时必须与订单记录的第三方流水号一致';

COMMENT ON CONSTRAINT uk_membership_payment_callback_order_id
    ON membership_payment_callback IS
    '同一会员业务订单只保留首次合法支付回调事实；后续合法通知统一由幂等链路返回 success';
COMMENT ON CONSTRAINT uk_membership_payment_callback_provider_trade_no
    ON membership_payment_callback IS
    '同一第三方支付流水只允许绑定一次，禁止跨会员订单复用';

COMMIT;
