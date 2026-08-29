BEGIN;

-- 任何既有用户如果同时保留多笔 PENDING/CLOSING，必须先人工裁决；迁移禁止猜测哪笔订单应被关闭。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM membership_order
        WHERE status IN (0, 1)
        GROUP BY login_identity_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Duplicate active membership orders must be resolved before migration';
    END IF;
END
$$;

ALTER TABLE membership_order
    ADD COLUMN IF NOT EXISTS entitlement_resolution VARCHAR(32),
    ADD COLUMN IF NOT EXISTS entitlement_resolved_at TIMESTAMPTZ;

-- 既有退款裁决只补齐订单内部裁决，不触发退款或修改订单状态。
UPDATE membership_order AS membership_order
SET entitlement_resolution = 'REFUND_REQUIRED',
    entitlement_resolved_at = callback.resolved_at
FROM membership_payment_callback AS callback
WHERE callback.order_id = membership_order.id
  AND callback.resolution = 'REFUND_REQUIRED'
  AND callback.resolved_at IS NOT NULL
  AND membership_order.entitlement_resolution IS NULL;

-- 部署前的 PAID 记录缺少原子发放证据，必须明确标为历史不补发，禁止上线后重复赠送额度。
UPDATE membership_order
SET entitlement_resolution = 'LEGACY_NOT_GRANTED',
    entitlement_resolved_at = COALESCE(
        paid_at,
        updated_at,
        created_at,
        CURRENT_TIMESTAMP)
WHERE status = 2
  AND entitlement_resolution IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'membership_order'::regclass
          AND conname = 'chk_membership_order_entitlement_resolution'
    ) THEN
        ALTER TABLE membership_order
            ADD CONSTRAINT chk_membership_order_entitlement_resolution
            CHECK (
                entitlement_resolution IS NULL
                OR entitlement_resolution IN (
                    'APPLIED',
                    'NOT_GRANTED',
                    'REFUND_REQUIRED',
                    'LEGACY_NOT_GRANTED'
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'membership_order'::regclass
          AND conname = 'chk_membership_order_entitlement_resolution_time'
    ) THEN
        ALTER TABLE membership_order
            ADD CONSTRAINT chk_membership_order_entitlement_resolution_time
            CHECK (
                (entitlement_resolution IS NULL AND entitlement_resolved_at IS NULL)
                OR (
                    entitlement_resolution IS NOT NULL
                    AND entitlement_resolved_at IS NOT NULL
                )
            );
    END IF;
END
$$;

COMMENT ON COLUMN membership_order.entitlement_resolution IS
    '订单权益裁决：APPLIED=已原子发放套餐，NOT_GRANTED=未付款终态且未发放，REFUND_REQUIRED=终态后确认付款且不得发放、需要退款，LEGACY_NOT_GRANTED=部署前历史已支付订单不自动补发；为空表示仍未裁决';
COMMENT ON COLUMN membership_order.entitlement_resolved_at IS
    '订单权益裁决完成时间；必须与 entitlement_resolution 同时为空或同时非空';

CREATE OR REPLACE VIEW public.membership_order_readable
WITH (security_invoker = true)
AS
SELECT
    public.hybrid_id_to_base64url(id) AS id_base64url,
    login_identity_id,
    membership_tier,
    pay_amount_yuan,
    pay_type,
    status,
    idempotency_key,
    provider_trade_no,
    payment_started_at,
    expires_at,
    closing_deadline_at,
    paid_at,
    entitlement_resolution,
    entitlement_resolved_at,
    state_version,
    created_at,
    updated_at
FROM public.membership_order;

COMMIT;
