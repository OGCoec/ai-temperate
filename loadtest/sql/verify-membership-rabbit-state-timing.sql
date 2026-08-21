\set ON_ERROR_STOP on

CREATE TEMP TABLE rabbit_orders (
    run_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    order_id TEXT NOT NULL,
    scenario TEXT NOT NULL,
    expected_status TEXT NOT NULL
);

\copy rabbit_orders FROM :'scenario_csv' CSV HEADER

-- 最终 Rabbit 消息和两级持久化最多再等待一分钟；未收敛直接终止验收。
DO $$
DECLARE
    deadline TIMESTAMPTZ := clock_timestamp() + INTERVAL '1 minute';
    unfinished BIGINT;
BEGIN
    LOOP
        SELECT COUNT(*)
        INTO unfinished
        FROM rabbit_orders test
        LEFT JOIN membership_order payment_order
            ON payment_order.idempotency_key = test.idempotency_key
        WHERE payment_order.id IS NULL
           OR (test.expected_status = 'PAID' AND payment_order.status <> 2)
           OR (test.expected_status = 'CANCELLED' AND payment_order.status <> 3)
           OR (test.expected_status = 'CLOSED' AND payment_order.status <> 4);
        EXIT WHEN unfinished = 0;
        IF clock_timestamp() >= deadline THEN
            RAISE EXCEPTION 'Rabbit timing scenarios did not settle; unfinished=%', unfinished;
        END IF;
        PERFORM pg_sleep(1);
    END LOOP;
END
$$;

CREATE TEMP VIEW scoped_rabbit_orders AS
SELECT test.*,
       payment_order.id AS internal_order_id,
       payment_order.status AS actual_status,
       payment_order.payment_started_at,
       payment_order.expires_at,
       payment_order.closing_deadline_at,
       payment_order.state_version
FROM rabbit_orders test
LEFT JOIN membership_order payment_order
    ON payment_order.idempotency_key = test.idempotency_key;

\echo membership_rabbit_state_failures
SELECT scoped.scenario,
       scoped.expected_status,
       scoped.actual_status,
       scoped.payment_started_at,
       scoped.expires_at,
       scoped.closing_deadline_at,
       scoped.state_version,
       COUNT(callback.id) AS callback_count,
       MAX(callback.resolution) AS callback_resolution
FROM scoped_rabbit_orders scoped
LEFT JOIN membership_payment_callback callback
    ON callback.order_id = scoped.internal_order_id
GROUP BY scoped.scenario,
         scoped.expected_status,
         scoped.actual_status,
         scoped.payment_started_at,
         scoped.expires_at,
         scoped.closing_deadline_at,
         scoped.state_version
HAVING scoped.actual_status IS NULL
    OR (scoped.expected_status = 'PAID' AND scoped.actual_status <> 2)
    OR (scoped.expected_status = 'CANCELLED' AND scoped.actual_status <> 3)
    OR (scoped.expected_status = 'CLOSED' AND scoped.actual_status <> 4)
    OR scoped.state_version <= 0
    OR (scoped.scenario IN ('Q-02', 'Q-03', 'Q-06')
        AND scoped.closing_deadline_at
            IS DISTINCT FROM scoped.expires_at + INTERVAL '5 minutes')
    OR (scoped.scenario = 'Q-06'
        AND (scoped.payment_started_at IS NULL
            OR scoped.payment_started_at >= scoped.expires_at
            OR COUNT(callback.id) <> 1
            OR COUNT(callback.id) FILTER (WHERE callback.resolution = 'APPLIED') <> 1))
    OR (scoped.scenario <> 'Q-06' AND COUNT(callback.id) <> 0);

\echo membership_rabbit_state_timing_verdict
WITH grouped AS (
    SELECT scoped.scenario,
           scoped.expected_status,
           scoped.actual_status,
           scoped.payment_started_at,
           scoped.expires_at,
           scoped.closing_deadline_at,
           scoped.state_version,
           scoped.internal_order_id,
           COUNT(callback.id) AS callback_count,
           COUNT(callback.id) FILTER (
               WHERE callback.resolution = 'APPLIED') AS applied_count
    FROM scoped_rabbit_orders scoped
    LEFT JOIN membership_payment_callback callback
        ON callback.order_id = scoped.internal_order_id
    GROUP BY scoped.scenario,
             scoped.expected_status,
             scoped.actual_status,
             scoped.payment_started_at,
             scoped.expires_at,
             scoped.closing_deadline_at,
             scoped.state_version,
             scoped.internal_order_id
), failures AS (
    SELECT 1
    FROM grouped
    WHERE internal_order_id IS NULL
       OR (expected_status = 'PAID' AND actual_status <> 2)
       OR (expected_status = 'CANCELLED' AND actual_status <> 3)
       OR (expected_status = 'CLOSED' AND actual_status <> 4)
       OR state_version <= 0
       OR (scenario IN ('Q-02', 'Q-03', 'Q-06')
            AND closing_deadline_at
                IS DISTINCT FROM expires_at + INTERVAL '5 minutes')
       OR (scenario = 'Q-06'
            AND (payment_started_at IS NULL
                OR payment_started_at >= expires_at
                OR callback_count <> 1
                OR applied_count <> 1))
       OR (scenario <> 'Q-06' AND callback_count <> 0)
)
SELECT CASE WHEN EXISTS (SELECT 1 FROM failures) THEN 'FAIL' ELSE 'PASS' END AS verdict;
