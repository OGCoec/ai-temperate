BEGIN;

ALTER TABLE membership_order
    DROP CONSTRAINT IF EXISTS chk_membership_order_entitlement_resolution;

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

/* 先保留已由回调审计明确裁定的退款事实，避免把迟到支付错误降格为普通未发放。 */
UPDATE membership_order AS payment_order
SET entitlement_resolution = 'REFUND_REQUIRED',
    entitlement_resolved_at = callback.resolved_at
FROM membership_payment_callback AS callback
WHERE callback.order_id = payment_order.id
  AND callback.resolution = 'REFUND_REQUIRED'
  AND callback.resolved_at IS NOT NULL
  AND payment_order.entitlement_resolution IS NULL;

/* 无支付事实的取消或关闭订单在迁移后具备显式、可审计的未发放裁决。 */
UPDATE membership_order
SET entitlement_resolution = 'NOT_GRANTED',
    entitlement_resolved_at = updated_at
WHERE status IN (3, 4)
  AND paid_at IS NULL
  AND entitlement_resolution IS NULL;

COMMENT ON COLUMN membership_order.entitlement_resolution IS
    '订单权益裁决：APPLIED=已原子发放套餐，NOT_GRANTED=未付款终态且未发放，REFUND_REQUIRED=终态后确认付款且不得发放、需要退款，LEGACY_NOT_GRANTED=部署前历史已支付订单不自动补发；为空表示仍未裁决';

COMMIT;
