\set ON_ERROR_STOP on

CREATE TEMP TABLE auth_orders (
    run_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    order_id TEXT NOT NULL,
    case_name TEXT NOT NULL,
    expected_status TEXT NOT NULL,
    expected_callback_count INTEGER NOT NULL
);

\copy auth_orders FROM :'scenario_csv' CSV HEADER

DO $$
DECLARE
    deadline TIMESTAMPTZ := clock_timestamp() + INTERVAL '2 minutes';
    unfinished BIGINT;
BEGIN
    LOOP
        SELECT COUNT(*)
        INTO unfinished
        FROM auth_orders auth
        LEFT JOIN membership_order payment_order
            ON payment_order.idempotency_key = auth.idempotency_key
        WHERE payment_order.id IS NULL
           OR payment_order.status <> 3
           OR (SELECT COUNT(*)
               FROM membership_payment_callback callback
               WHERE callback.order_id = payment_order.id)
                <> auth.expected_callback_count;
        EXIT WHEN unfinished = 0;
        IF clock_timestamp() >= deadline THEN
            RAISE EXCEPTION 'Auth boundary database facts did not settle; unfinished=%', unfinished;
        END IF;
        PERFORM pg_sleep(1);
    END LOOP;
END
$$;

\echo auth_boundary_fact_failures
SELECT auth.case_name,
       auth.expected_status,
       payment_order.status AS actual_status,
       auth.expected_callback_count,
       COUNT(callback.id) AS actual_callback_count
FROM auth_orders auth
LEFT JOIN membership_order payment_order
    ON payment_order.idempotency_key = auth.idempotency_key
LEFT JOIN membership_payment_callback callback
    ON callback.order_id = payment_order.id
GROUP BY auth.case_name,
         auth.expected_status,
         payment_order.status,
         auth.expected_callback_count
HAVING payment_order.status IS NULL
    OR payment_order.status <> 3
    OR COUNT(callback.id) <> auth.expected_callback_count;

\echo auth_boundary_verdict
WITH facts AS (
    SELECT auth.case_name,
           payment_order.status AS actual_status,
           auth.expected_callback_count,
           COUNT(callback.id) AS actual_callback_count
    FROM auth_orders auth
    LEFT JOIN membership_order payment_order
        ON payment_order.idempotency_key = auth.idempotency_key
    LEFT JOIN membership_payment_callback callback
        ON callback.order_id = payment_order.id
    GROUP BY auth.case_name,
             payment_order.status,
             auth.expected_callback_count
)
SELECT CASE
    WHEN EXISTS (
        SELECT 1
        FROM facts
        WHERE actual_status IS NULL
           OR actual_status <> 3
           OR actual_callback_count <> expected_callback_count
    ) THEN 'FAIL'
    ELSE 'PASS'
END AS verdict;
