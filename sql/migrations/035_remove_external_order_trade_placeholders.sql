-- 该迁移先清理从未发起外部请求的历史占位值，再阻断仍需平台查询的已发起记录，禁止盲目猜测 Provider 事实。
BEGIN;

-- 从未发起支付的占位值没有第三方事实，可以安全恢复为 NULL。
UPDATE membership_order
SET provider_trade_no = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE payment_started_at IS NULL
  AND (
      provider_trade_no LIKE 'LIUHAO:ORDER:%'
      OR provider_trade_no LIKE 'BAR:ORDER:%'
  );

-- 已发起记录必须先由运维使用旧前缀逐笔查询对应平台；任何残留都会阻断约束上线。
DO $$
DECLARE
    unresolved_count BIGINT;
BEGIN
    SELECT COUNT(*)
    INTO unresolved_count
    FROM membership_order
    WHERE provider_trade_no LIKE 'LIUHAO:ORDER:%'
       OR provider_trade_no LIKE 'BAR:ORDER:%';

    IF unresolved_count <> 0 THEN
        RAISE EXCEPTION
            'External ORDER placeholders remain: %. Reconcile them to real TRADE references or verified terminal NULL before retrying.',
            unresolved_count;
    END IF;
END
$$;

-- 约束只禁止历史伪交易号；全新数据库已由基础建表脚本创建时保持幂等。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_membership_order_no_external_order_reference'
          AND conrelid = 'membership_order'::regclass
    ) THEN
        ALTER TABLE membership_order
            ADD CONSTRAINT chk_membership_order_no_external_order_reference
            CHECK (
                provider_trade_no IS NULL
                OR (
                    provider_trade_no NOT LIKE 'LIUHAO:ORDER:%'
                    AND provider_trade_no NOT LIKE 'BAR:ORDER:%'
                )
            ) NOT VALID;
    END IF;
END
$$;

-- 前置阻断保证存量已经清零，此处再验证全部历史数据并启用写入保护。
ALTER TABLE membership_order
    VALIDATE CONSTRAINT chk_membership_order_no_external_order_reference;

COMMENT ON CONSTRAINT chk_membership_order_no_external_order_reference
    ON membership_order IS
    '禁止 BAR 与六号把本地订单 ID 以 PROVIDER:ORDER 形式伪装成第三方交易流水';

COMMIT;
