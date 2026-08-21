\set ON_ERROR_STOP on

-- 本次 JMeter 输入只进入临时表；验收脚本不修改会员支付业务表。
CREATE TEMP TABLE loadtest_orders (
    run_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    order_id TEXT NOT NULL,
    scenario TEXT NOT NULL,
    scenario_group TEXT NOT NULL,
    expected_status TEXT NOT NULL,
    expected_resolution TEXT NOT NULL,
    target_callback_at TIMESTAMPTZ NOT NULL,
    actual_callback_at TIMESTAMPTZ NOT NULL,
    callback_drift_millis BIGINT NOT NULL,
    provider_trade_no TEXT NOT NULL,
    protocol TEXT NOT NULL
);

\copy loadtest_orders FROM :'scenario_csv' CSV HEADER

DO $$
DECLARE
    deadline TIMESTAMPTZ := clock_timestamp() + INTERVAL '2 minutes';
    unfinished BIGINT;
BEGIN
    LOOP
        SELECT COUNT(*)
        INTO unfinished
        FROM loadtest_orders test
        LEFT JOIN membership_order payment_order
            ON payment_order.idempotency_key = test.idempotency_key
        WHERE payment_order.id IS NULL
           OR (test.expected_status = 'PAID' AND payment_order.status <> 2)
           OR (test.expected_status = 'CANCELLED' AND payment_order.status <> 3)
           OR (test.expected_status = 'CLOSED' AND payment_order.status <> 4)
           OR NOT EXISTS (
               SELECT 1
               FROM membership_payment_callback callback
               WHERE callback.order_id = payment_order.id
                 AND callback.resolution = test.expected_resolution);
        EXIT WHEN unfinished = 0;
        IF clock_timestamp() >= deadline THEN
            RAISE EXCEPTION 'State-machine database facts did not settle; unfinished=%', unfinished;
        END IF;
        PERFORM pg_sleep(1);
    END LOOP;
END
$$;

CREATE TEMP VIEW scoped_orders AS
SELECT
    test.*,
    payment_order.id AS internal_order_id,
    payment_order.status AS actual_status,
    payment_order.provider_trade_no AS actual_provider_trade_no,
    payment_order.payment_started_at,
    payment_order.expires_at,
    payment_order.closing_deadline_at,
    payment_order.state_version
FROM loadtest_orders test
LEFT JOIN membership_order payment_order
    ON payment_order.idempotency_key = test.idempotency_key;

CREATE TEMP VIEW scoped_callbacks AS
SELECT
    scoped.scenario,
    callback.id,
    callback.order_id,
    callback.provider_trade_no,
    callback.received_at,
    callback.resolution,
    callback.resolved_at
FROM scoped_orders scoped
JOIN membership_payment_callback callback
    ON callback.order_id = scoped.internal_order_id;

\echo membership_order_identity_and_terminal_failures
SELECT scenario,
       order_id,
       actual_status,
       state_version,
       CASE
           WHEN internal_order_id IS NULL THEN 'MISSING_ORDER'
           WHEN state_version <= 0 THEN 'INVALID_STATE_VERSION'
           WHEN expected_status = 'PAID' AND actual_status <> 2 THEN 'EXPECTED_PAID'
           WHEN expected_status = 'CANCELLED' AND actual_status <> 3 THEN 'EXPECTED_CANCELLED'
           WHEN expected_status = 'CLOSED' AND actual_status <> 4 THEN 'EXPECTED_CLOSED'
           WHEN actual_status IN (0, 1) THEN 'NON_TERMINAL_ORDER'
           ELSE NULL
       END AS failure
FROM scoped_orders
WHERE internal_order_id IS NULL
   OR state_version <= 0
   OR (expected_status = 'PAID' AND actual_status <> 2)
   OR (expected_status = 'CANCELLED' AND actual_status <> 3)
   OR (expected_status = 'CLOSED' AND actual_status <> 4)
   OR actual_status IN (0, 1);

\echo membership_payment_timing_failures
SELECT scoped.scenario,
       scoped.scenario_group,
       scoped.payment_started_at,
       scoped.expires_at,
       scoped.closing_deadline_at,
       callback.received_at,
       CASE
           WHEN scoped.scenario <> 'X-01'
                AND (scoped.payment_started_at IS NULL
                    OR scoped.payment_started_at >= scoped.expires_at)
               THEN 'PAYMENT_NOT_STARTED_BEFORE_EXPIRY'
           WHEN scoped.scenario_group IN ('CLOSING', 'CLOSED')
                AND scoped.closing_deadline_at
                    IS DISTINCT FROM scoped.expires_at + INTERVAL '5 minutes'
               THEN 'INVALID_HARD_CLOSE_DEADLINE'
           WHEN scoped.scenario_group IN ('PENDING_PAYMENT', 'PAID')
                AND callback.received_at >= scoped.expires_at
               THEN 'PENDING_CALLBACK_ARRIVED_TOO_LATE'
           WHEN scoped.scenario_group = 'CLOSING'
                AND (callback.received_at < scoped.expires_at
                    OR callback.received_at >= scoped.expires_at + INTERVAL '5 minutes')
               THEN 'CLOSING_CALLBACK_OUTSIDE_WINDOW'
           WHEN scoped.scenario_group = 'CLOSED'
                AND callback.received_at < scoped.expires_at + INTERVAL '5 minutes'
               THEN 'CLOSED_CALLBACK_BEFORE_HARD_CLOSE'
           ELSE NULL
       END AS failure
FROM scoped_orders scoped
LEFT JOIN scoped_callbacks callback
    ON callback.scenario = scoped.scenario
WHERE (scoped.scenario <> 'X-01'
        AND (scoped.payment_started_at IS NULL
            OR scoped.payment_started_at >= scoped.expires_at))
   OR (scoped.scenario_group IN ('CLOSING', 'CLOSED')
        AND scoped.closing_deadline_at
            IS DISTINCT FROM scoped.expires_at + INTERVAL '5 minutes')
   OR (scoped.scenario_group IN ('PENDING_PAYMENT', 'PAID')
        AND callback.received_at >= scoped.expires_at)
   OR (scoped.scenario_group = 'CLOSING'
        AND (callback.received_at < scoped.expires_at
            OR callback.received_at >= scoped.expires_at + INTERVAL '5 minutes'))
   OR (scoped.scenario_group = 'CLOSED'
        AND callback.received_at < scoped.expires_at + INTERVAL '5 minutes');

\echo membership_payment_callback_fact_failures
SELECT scoped.scenario,
       COUNT(callback.id) AS callback_count,
       COUNT(callback.id) FILTER (
           WHERE callback.resolution = scoped.expected_resolution) AS expected_resolution_count,
       COUNT(callback.id) FILTER (
           WHERE callback.resolution = 'REFUND_REQUIRED') AS refund_required_count,
       COUNT(callback.id) FILTER (
           WHERE callback.resolution = 'ALREADY_APPLIED') AS already_applied_count
FROM scoped_orders scoped
LEFT JOIN membership_payment_callback callback
    ON callback.order_id = scoped.internal_order_id
GROUP BY scoped.scenario, scoped.expected_resolution
HAVING COUNT(callback.id) <> 1
    OR COUNT(callback.id) FILTER (
        WHERE callback.resolution = scoped.expected_resolution) <> 1
    OR COUNT(callback.id) FILTER (
        WHERE callback.resolution = 'ALREADY_APPLIED') <> 0;

\echo membership_payment_callback_order_duplicates
SELECT callback.order_id, COUNT(*) AS callback_count
FROM membership_payment_callback callback
WHERE callback.order_id IN (
    SELECT internal_order_id FROM scoped_orders WHERE internal_order_id IS NOT NULL
)
GROUP BY callback.order_id
HAVING COUNT(*) > 1;

\echo membership_payment_callback_provider_trade_duplicates
SELECT callback.provider_trade_no, COUNT(*) AS callback_count
FROM membership_payment_callback callback
WHERE callback.order_id IN (
    SELECT internal_order_id FROM scoped_orders WHERE internal_order_id IS NOT NULL
)
GROUP BY callback.provider_trade_no
HAVING COUNT(*) > 1;

\echo membership_payment_callback_provider_overwrite_failures
SELECT scoped.scenario,
       scoped.provider_trade_no AS expected_provider_trade_no,
       scoped.actual_provider_trade_no AS order_provider_trade_no,
       callback.provider_trade_no AS callback_provider_trade_no
FROM scoped_orders scoped
LEFT JOIN membership_payment_callback callback
    ON callback.order_id = scoped.internal_order_id
WHERE callback.provider_trade_no IS DISTINCT FROM scoped.provider_trade_no
   OR (scoped.expected_status = 'PAID'
       AND scoped.actual_provider_trade_no IS DISTINCT FROM scoped.provider_trade_no);

\echo membership_payment_callback_orphans
SELECT callback.id, callback.order_id, callback.provider_trade_no
FROM membership_payment_callback callback
LEFT JOIN membership_order payment_order
    ON payment_order.id = callback.order_id
WHERE payment_order.id IS NULL;

\echo membership_payment_verdict
WITH failures AS (
    SELECT 1
    FROM scoped_orders scoped
    LEFT JOIN membership_payment_callback callback
        ON callback.order_id = scoped.internal_order_id
    WHERE scoped.internal_order_id IS NULL
       OR scoped.state_version <= 0
       OR scoped.actual_status IN (0, 1)
       OR (scoped.expected_status = 'PAID' AND scoped.actual_status <> 2)
       OR (scoped.expected_status = 'CANCELLED' AND scoped.actual_status <> 3)
       OR (scoped.expected_status = 'CLOSED' AND scoped.actual_status <> 4)
       OR (scoped.scenario <> 'X-01'
           AND (scoped.payment_started_at IS NULL
               OR scoped.payment_started_at >= scoped.expires_at))
       OR (scoped.scenario_group IN ('CLOSING', 'CLOSED')
           AND scoped.closing_deadline_at
               IS DISTINCT FROM scoped.expires_at + INTERVAL '5 minutes')
       OR callback.id IS NULL
       OR callback.resolution IS DISTINCT FROM scoped.expected_resolution
       OR callback.resolution = 'ALREADY_APPLIED'
       OR callback.provider_trade_no IS DISTINCT FROM scoped.provider_trade_no
       OR (scoped.expected_status = 'PAID'
           AND scoped.actual_provider_trade_no IS DISTINCT FROM scoped.provider_trade_no)
       OR (scoped.scenario_group IN ('PENDING_PAYMENT', 'PAID')
           AND callback.received_at >= scoped.expires_at)
       OR (scoped.scenario_group = 'CLOSING'
           AND (callback.received_at < scoped.expires_at
               OR callback.received_at >= scoped.expires_at + INTERVAL '5 minutes'))
       OR (scoped.scenario_group = 'CLOSED'
           AND callback.received_at < scoped.expires_at + INTERVAL '5 minutes')
    UNION ALL
    SELECT 1
    FROM membership_payment_callback callback
    WHERE callback.order_id IN (
        SELECT internal_order_id FROM scoped_orders WHERE internal_order_id IS NOT NULL
    )
    GROUP BY callback.order_id
    HAVING COUNT(*) > 1
    UNION ALL
    SELECT 1
    FROM membership_payment_callback callback
    WHERE callback.order_id IN (
        SELECT internal_order_id FROM scoped_orders WHERE internal_order_id IS NOT NULL
    )
    GROUP BY callback.provider_trade_no
    HAVING COUNT(*) > 1
)
SELECT CASE WHEN EXISTS (SELECT 1 FROM failures) THEN 'FAIL' ELSE 'PASS' END AS verdict;
