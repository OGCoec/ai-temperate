\set ON_ERROR_STOP on

CREATE TEMP TABLE loadtest_batch_orders (
    run_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    order_id TEXT NOT NULL,
    batch_size INTEGER NOT NULL,
    ordinal INTEGER NOT NULL,
    expected_status TEXT NOT NULL,
    expected_resolution TEXT NOT NULL,
    provider_trade_no TEXT NOT NULL
);

\copy loadtest_batch_orders FROM :'scenario_csv' CSV HEADER

-- 最多等待两分钟让 callback 和 dirty 两级调度器完成；超时直接使 Runner 非零退出。
DO $$
DECLARE
    deadline TIMESTAMPTZ := clock_timestamp() + INTERVAL '2 minutes';
    unfinished BIGINT;
BEGIN
    LOOP
        SELECT COUNT(*)
        INTO unfinished
        FROM loadtest_batch_orders test
        LEFT JOIN membership_order payment_order
            ON payment_order.idempotency_key = test.idempotency_key
        LEFT JOIN membership_payment_callback callback
            ON callback.order_id = payment_order.id
        WHERE payment_order.id IS NULL
           OR payment_order.status <> 2
           OR callback.id IS NULL
           OR callback.resolution <> 'APPLIED';

        EXIT WHEN unfinished = 0;
        IF clock_timestamp() >= deadline THEN
            RAISE EXCEPTION 'Persistence batch did not settle; unfinished=%', unfinished;
        END IF;
        PERFORM pg_sleep(2);
    END LOOP;
END
$$;

CREATE TEMP VIEW scoped_batch_orders AS
SELECT test.*,
       payment_order.id AS internal_order_id,
       payment_order.status AS actual_status,
       payment_order.provider_trade_no AS actual_provider_trade_no,
       payment_order.payment_started_at,
       payment_order.expires_at,
       payment_order.state_version
FROM loadtest_batch_orders test
LEFT JOIN membership_order payment_order
    ON payment_order.idempotency_key = test.idempotency_key;

\echo membership_persistence_batch_size_failures
SELECT batch_size, COUNT(*) AS actual_count, MAX(ordinal) AS maximum_ordinal
FROM loadtest_batch_orders
GROUP BY batch_size
HAVING COUNT(*) <> batch_size OR MIN(ordinal) <> 1 OR MAX(ordinal) <> batch_size;

\echo membership_persistence_fact_failures
SELECT scoped.batch_size,
       scoped.ordinal,
       scoped.order_id,
       scoped.actual_status,
       scoped.state_version,
       scoped.actual_provider_trade_no,
       COUNT(callback.id) AS callback_count,
       MAX(callback.resolution) AS callback_resolution
FROM scoped_batch_orders scoped
LEFT JOIN membership_payment_callback callback
    ON callback.order_id = scoped.internal_order_id
GROUP BY scoped.batch_size,
         scoped.ordinal,
         scoped.order_id,
         scoped.actual_status,
         scoped.state_version,
         scoped.actual_provider_trade_no,
         scoped.provider_trade_no,
         scoped.payment_started_at,
         scoped.expires_at
HAVING scoped.actual_status IS DISTINCT FROM 2
    OR scoped.state_version < 3
    OR scoped.actual_provider_trade_no IS DISTINCT FROM scoped.provider_trade_no
    OR scoped.payment_started_at IS NULL
    OR scoped.payment_started_at >= scoped.expires_at
    OR COUNT(callback.id) <> 1
    OR COUNT(callback.id) FILTER (WHERE callback.resolution = 'APPLIED') <> 1;

\echo membership_persistence_duplicate_order_failures
SELECT callback.order_id, COUNT(*) AS callback_count
FROM membership_payment_callback callback
WHERE callback.order_id IN (
    SELECT internal_order_id FROM scoped_batch_orders WHERE internal_order_id IS NOT NULL
)
GROUP BY callback.order_id
HAVING COUNT(*) > 1;

\echo membership_persistence_duplicate_provider_failures
SELECT callback.provider_trade_no, COUNT(*) AS callback_count
FROM membership_payment_callback callback
WHERE callback.order_id IN (
    SELECT internal_order_id FROM scoped_batch_orders WHERE internal_order_id IS NOT NULL
)
GROUP BY callback.provider_trade_no
HAVING COUNT(*) > 1;

\echo membership_persistence_batch_verdict
WITH failures AS (
    SELECT 1
    FROM loadtest_batch_orders
    GROUP BY batch_size
    HAVING COUNT(*) <> batch_size OR MIN(ordinal) <> 1 OR MAX(ordinal) <> batch_size
    UNION ALL
    SELECT 1
    FROM scoped_batch_orders scoped
    LEFT JOIN membership_payment_callback callback
        ON callback.order_id = scoped.internal_order_id
    GROUP BY scoped.internal_order_id,
             scoped.actual_status,
             scoped.state_version,
             scoped.actual_provider_trade_no,
             scoped.provider_trade_no,
             scoped.payment_started_at,
             scoped.expires_at
    HAVING scoped.internal_order_id IS NULL
        OR scoped.actual_status <> 2
        OR scoped.state_version < 3
        OR scoped.actual_provider_trade_no IS DISTINCT FROM scoped.provider_trade_no
        OR scoped.payment_started_at IS NULL
        OR scoped.payment_started_at >= scoped.expires_at
        OR COUNT(callback.id) <> 1
        OR COUNT(callback.id) FILTER (WHERE callback.resolution = 'APPLIED') <> 1
    UNION ALL
    SELECT 1
    FROM membership_payment_callback callback
    WHERE callback.order_id IN (
        SELECT internal_order_id FROM scoped_batch_orders WHERE internal_order_id IS NOT NULL
    )
    GROUP BY callback.order_id
    HAVING COUNT(*) > 1
    UNION ALL
    SELECT 1
    FROM membership_payment_callback callback
    WHERE callback.order_id IN (
        SELECT internal_order_id FROM scoped_batch_orders WHERE internal_order_id IS NOT NULL
    )
    GROUP BY callback.provider_trade_no
    HAVING COUNT(*) > 1
)
SELECT CASE WHEN EXISTS (SELECT 1 FROM failures) THEN 'FAIL' ELSE 'PASS' END AS verdict;
