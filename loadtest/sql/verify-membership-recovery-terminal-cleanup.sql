\set ON_ERROR_STOP on

CREATE TEMP TABLE recovery_orders (
    run_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    order_id TEXT NOT NULL,
    case_name TEXT NOT NULL,
    expected_status TEXT NOT NULL,
    expected_resolution TEXT NOT NULL,
    -- CANCELLED/CLOSED 终态清理案例没有成功回调，因此第三方流水按业务语义必须允许为空。
    provider_trade_no TEXT,
    recovered_claimed INTEGER NOT NULL,
    recovered_count INTEGER NOT NULL,
    fault_before BIGINT NOT NULL,
    fault_after BIGINT NOT NULL,
    redis_clean BOOLEAN NOT NULL
);

\copy recovery_orders FROM :'scenario_csv' CSV HEADER

-- 恢复入口已经主动执行正式批处理，这里仍给数据库最终状态最多一分钟收敛时间。
DO $$
DECLARE
    deadline TIMESTAMPTZ := clock_timestamp() + INTERVAL '1 minute';
    unfinished BIGINT;
BEGIN
    LOOP
        SELECT COUNT(*)
        INTO unfinished
        FROM recovery_orders test
        LEFT JOIN membership_order payment_order
            ON payment_order.idempotency_key = test.idempotency_key
        WHERE payment_order.id IS NULL
           OR (test.expected_status = 'PAID' AND payment_order.status <> 2)
           OR (test.expected_status = 'CANCELLED' AND payment_order.status <> 3)
           OR (test.expected_status = 'CLOSED' AND payment_order.status <> 4);

        EXIT WHEN unfinished = 0;
        IF clock_timestamp() >= deadline THEN
            RAISE EXCEPTION 'Recovery scenarios did not settle; unfinished=%', unfinished;
        END IF;
        PERFORM pg_sleep(1);
    END LOOP;
END
$$;

CREATE TEMP VIEW scoped_recovery_orders AS
SELECT test.*,
       payment_order.id AS internal_order_id,
       payment_order.status AS actual_status,
       payment_order.provider_trade_no AS actual_provider_trade_no,
       payment_order.payment_started_at,
       payment_order.expires_at,
       payment_order.closing_deadline_at,
       payment_order.state_version
FROM recovery_orders test
LEFT JOIN membership_order payment_order
    ON payment_order.idempotency_key = test.idempotency_key;

\echo membership_recovery_order_failures
SELECT case_name,
       order_id,
       expected_status,
       actual_status,
       state_version,
       redis_clean
FROM scoped_recovery_orders
WHERE internal_order_id IS NULL
   OR (expected_status = 'PAID' AND actual_status <> 2)
   OR (expected_status = 'CANCELLED' AND actual_status <> 3)
   OR (expected_status = 'CLOSED' AND actual_status <> 4)
   OR actual_status IN (0, 1)
   OR state_version <= 0
   OR redis_clean IS NOT TRUE;

\echo membership_recovery_probe_failures
SELECT case_name, recovered_claimed, recovered_count, fault_before, fault_after
FROM scoped_recovery_orders
WHERE (case_name IN ('RC-01', 'RC-02')
       AND (recovered_claimed <> 1 OR recovered_count <> 1))
   OR (case_name = 'RC-03' AND fault_after <> fault_before + 1);

\echo membership_recovery_callback_failures
SELECT scoped.case_name,
       scoped.expected_resolution,
       COUNT(callback.id) AS callback_count,
       MAX(callback.resolution) AS actual_resolution,
       scoped.provider_trade_no AS expected_provider_trade_no,
       MAX(callback.provider_trade_no) AS actual_provider_trade_no
FROM scoped_recovery_orders scoped
LEFT JOIN membership_payment_callback callback
    ON callback.order_id = scoped.internal_order_id
GROUP BY scoped.case_name, scoped.expected_resolution, scoped.provider_trade_no
HAVING (scoped.expected_resolution = 'NONE' AND COUNT(callback.id) <> 0)
    OR (scoped.expected_resolution <> 'NONE'
        AND (COUNT(callback.id) <> 1
            OR COUNT(callback.id) FILTER (
                WHERE callback.resolution = scoped.expected_resolution) <> 1
            OR MAX(callback.provider_trade_no)
                IS DISTINCT FROM scoped.provider_trade_no));

\echo membership_recovery_deadline_failures
SELECT case_name, expires_at, closing_deadline_at
FROM scoped_recovery_orders
WHERE expected_status = 'CLOSED'
  AND closing_deadline_at IS DISTINCT FROM expires_at + INTERVAL '5 minutes';

\echo membership_recovery_duplicate_order_failures
SELECT callback.order_id, COUNT(*) AS callback_count
FROM membership_payment_callback callback
WHERE callback.order_id IN (
    SELECT internal_order_id FROM scoped_recovery_orders WHERE internal_order_id IS NOT NULL
)
GROUP BY callback.order_id
HAVING COUNT(*) > 1;

\echo membership_recovery_duplicate_provider_failures
SELECT callback.provider_trade_no, COUNT(*) AS callback_count
FROM membership_payment_callback callback
WHERE callback.order_id IN (
    SELECT internal_order_id FROM scoped_recovery_orders WHERE internal_order_id IS NOT NULL
)
GROUP BY callback.provider_trade_no
HAVING COUNT(*) > 1;

\echo membership_recovery_terminal_cleanup_verdict
WITH grouped AS (
    SELECT scoped.case_name,
           scoped.expected_status,
           scoped.expected_resolution,
           scoped.actual_status,
           scoped.internal_order_id,
           scoped.state_version,
           scoped.redis_clean,
           scoped.recovered_claimed,
           scoped.recovered_count,
           scoped.fault_before,
           scoped.fault_after,
           scoped.provider_trade_no,
           scoped.expires_at,
           scoped.closing_deadline_at,
           COUNT(callback.id) AS callback_count,
           COUNT(callback.id) FILTER (
               WHERE callback.resolution = scoped.expected_resolution) AS matching_resolution_count,
           MAX(callback.provider_trade_no) AS actual_provider_trade_no
    FROM scoped_recovery_orders scoped
    LEFT JOIN membership_payment_callback callback
        ON callback.order_id = scoped.internal_order_id
    GROUP BY scoped.case_name,
             scoped.expected_status,
             scoped.expected_resolution,
             scoped.actual_status,
             scoped.internal_order_id,
             scoped.state_version,
             scoped.redis_clean,
             scoped.recovered_claimed,
             scoped.recovered_count,
             scoped.fault_before,
             scoped.fault_after,
             scoped.provider_trade_no,
             scoped.expires_at,
             scoped.closing_deadline_at
), failures AS (
    SELECT 1
    FROM grouped
    WHERE internal_order_id IS NULL
       OR (expected_status = 'PAID' AND actual_status <> 2)
       OR (expected_status = 'CANCELLED' AND actual_status <> 3)
       OR (expected_status = 'CLOSED' AND actual_status <> 4)
       OR actual_status IN (0, 1)
       OR state_version <= 0
       OR redis_clean IS NOT TRUE
       OR (case_name IN ('RC-01', 'RC-02')
            AND (recovered_claimed <> 1 OR recovered_count <> 1))
       OR (case_name = 'RC-03' AND fault_after <> fault_before + 1)
       OR (expected_resolution = 'NONE' AND callback_count <> 0)
       OR (expected_resolution <> 'NONE'
            AND (callback_count <> 1
                OR matching_resolution_count <> 1
                OR actual_provider_trade_no IS DISTINCT FROM provider_trade_no))
       OR (expected_status = 'CLOSED'
            AND closing_deadline_at
                IS DISTINCT FROM expires_at + INTERVAL '5 minutes')
    UNION ALL
    SELECT 1
    FROM membership_payment_callback callback
    WHERE callback.order_id IN (
        SELECT internal_order_id FROM scoped_recovery_orders WHERE internal_order_id IS NOT NULL
    )
    GROUP BY callback.order_id
    HAVING COUNT(*) > 1
    UNION ALL
    SELECT 1
    FROM membership_payment_callback callback
    WHERE callback.order_id IN (
        SELECT internal_order_id FROM scoped_recovery_orders WHERE internal_order_id IS NOT NULL
    )
    GROUP BY callback.provider_trade_no
    HAVING COUNT(*) > 1
)
SELECT CASE WHEN EXISTS (SELECT 1 FROM failures) THEN 'FAIL' ELSE 'PASS' END AS verdict;
