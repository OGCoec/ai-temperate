\set ON_ERROR_STOP on

CREATE TEMP TABLE race_orders (
    run_id TEXT NOT NULL,
    case_name TEXT NOT NULL,
    concurrency INTEGER NOT NULL,
    order_id TEXT NOT NULL,
    idempotency_key UUID NOT NULL,
    role TEXT NOT NULL,
    expected_status TEXT NOT NULL,
    expected_callback_count INTEGER NOT NULL,
    expected_resolution TEXT NOT NULL
);

\copy race_orders FROM :'scenario_csv' CSV HEADER

DO $$
DECLARE
    deadline TIMESTAMPTZ := clock_timestamp() + INTERVAL '2 minutes';
    unfinished BIGINT;
BEGIN
    LOOP
        SELECT COUNT(*)
        INTO unfinished
        FROM race_orders race
        LEFT JOIN membership_order payment_order
            ON payment_order.idempotency_key = race.idempotency_key
        WHERE payment_order.id IS NULL
           OR (race.expected_status = 'PAID' AND payment_order.status <> 2)
           OR (race.expected_status = 'CANCELLED' AND payment_order.status <> 3)
           OR (race.expected_status = 'CLOSED' AND payment_order.status <> 4)
           OR (SELECT COUNT(*)
               FROM membership_payment_callback callback
               WHERE callback.order_id = payment_order.id)
                <> race.expected_callback_count
           OR (race.expected_callback_count = 1
               AND NOT EXISTS (
                   SELECT 1
                   FROM membership_payment_callback callback
                   WHERE callback.order_id = payment_order.id
                     AND callback.resolution = race.expected_resolution));
        EXIT WHEN unfinished = 0;
        IF clock_timestamp() >= deadline THEN
            RAISE EXCEPTION 'Callback race database facts did not settle; unfinished=%', unfinished;
        END IF;
        PERFORM pg_sleep(1);
    END LOOP;
END
$$;

CREATE TEMP VIEW scoped_race_orders AS
SELECT race.*, payment_order.id AS internal_order_id,
       payment_order.status AS actual_status,
       payment_order.provider_trade_no
FROM race_orders race
LEFT JOIN membership_order payment_order
    ON payment_order.idempotency_key = race.idempotency_key;

\echo race_order_and_callback_fact_failures
SELECT scoped.case_name,
       scoped.role,
       scoped.expected_status,
       scoped.actual_status,
       scoped.expected_callback_count,
       COUNT(callback.id) AS actual_callback_count,
       scoped.expected_resolution,
       MAX(callback.resolution) AS actual_resolution
FROM scoped_race_orders scoped
LEFT JOIN membership_payment_callback callback
    ON callback.order_id = scoped.internal_order_id
GROUP BY scoped.case_name,
         scoped.role,
         scoped.expected_status,
         scoped.actual_status,
         scoped.expected_callback_count,
         scoped.expected_resolution
HAVING scoped.actual_status IS NULL
    OR (scoped.expected_status = 'PAID' AND scoped.actual_status <> 2)
    OR (scoped.expected_status = 'CANCELLED' AND scoped.actual_status <> 3)
    OR (scoped.expected_status = 'CLOSED' AND scoped.actual_status <> 4)
    OR COUNT(callback.id) <> scoped.expected_callback_count
    OR (scoped.expected_callback_count = 1
        AND COUNT(callback.id) FILTER (
            WHERE callback.resolution = scoped.expected_resolution) <> 1)
    OR (scoped.expected_callback_count = 0 AND COUNT(callback.id) <> 0);

\echo race_duplicate_order_ids
SELECT callback.order_id, COUNT(*) AS callback_count
FROM membership_payment_callback callback
WHERE callback.order_id IN (
    SELECT internal_order_id FROM scoped_race_orders WHERE internal_order_id IS NOT NULL
)
GROUP BY callback.order_id
HAVING COUNT(*) > 1;

\echo race_duplicate_provider_trade_numbers
SELECT callback.provider_trade_no, COUNT(*) AS callback_count
FROM membership_payment_callback callback
WHERE callback.order_id IN (
    SELECT internal_order_id FROM scoped_race_orders WHERE internal_order_id IS NOT NULL
)
GROUP BY callback.provider_trade_no
HAVING COUNT(*) > 1;

\echo race_paid_refund_failures
SELECT scoped.case_name, callback.provider_trade_no, callback.resolution
FROM scoped_race_orders scoped
JOIN membership_payment_callback callback
    ON callback.order_id = scoped.internal_order_id
WHERE scoped.actual_status = 2
  AND callback.resolution <> 'APPLIED';

\echo race_verdict
WITH grouped AS (
    SELECT scoped.case_name,
           scoped.role,
           scoped.expected_status,
           scoped.actual_status,
           scoped.expected_callback_count,
           scoped.expected_resolution,
           COUNT(callback.id) AS actual_callback_count,
           COUNT(callback.id) FILTER (
               WHERE callback.resolution = scoped.expected_resolution) AS matching_resolution_count
    FROM scoped_race_orders scoped
    LEFT JOIN membership_payment_callback callback
        ON callback.order_id = scoped.internal_order_id
    GROUP BY scoped.case_name,
             scoped.role,
             scoped.expected_status,
             scoped.actual_status,
             scoped.expected_callback_count,
             scoped.expected_resolution
), failures AS (
    SELECT 1
    FROM grouped
    WHERE actual_status IS NULL
       OR (expected_status = 'PAID' AND actual_status <> 2)
       OR (expected_status = 'CANCELLED' AND actual_status <> 3)
       OR (expected_status = 'CLOSED' AND actual_status <> 4)
       OR actual_callback_count <> expected_callback_count
       OR (expected_callback_count = 1 AND matching_resolution_count <> 1)
    UNION ALL
    SELECT 1
    FROM membership_payment_callback callback
    WHERE callback.order_id IN (
        SELECT internal_order_id FROM scoped_race_orders WHERE internal_order_id IS NOT NULL
    )
    GROUP BY callback.order_id
    HAVING COUNT(*) > 1
    UNION ALL
    SELECT 1
    FROM membership_payment_callback callback
    WHERE callback.order_id IN (
        SELECT internal_order_id FROM scoped_race_orders WHERE internal_order_id IS NOT NULL
    )
    GROUP BY callback.provider_trade_no
    HAVING COUNT(*) > 1
)
SELECT CASE WHEN EXISTS (SELECT 1 FROM failures) THEN 'FAIL' ELSE 'PASS' END AS verdict;
