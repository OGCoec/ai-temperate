\set ON_ERROR_STOP on

CREATE TEMP TABLE membership_millisecond_boundary_all_scenarios (
    run_id TEXT NOT NULL,
    wave_code TEXT NOT NULL,
    group_code TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    target_tier TEXT NOT NULL,
    order_id TEXT NOT NULL,
    planned_expires_at TIMESTAMPTZ(6) NOT NULL,
    planned_hard_close_at TIMESTAMPTZ(6) NOT NULL,
    target_offset_millis BIGINT NOT NULL,
    target_at TIMESTAMPTZ(6) NOT NULL
);

CREATE TEMP TABLE membership_millisecond_boundary_all_dispatches (
    run_id TEXT NOT NULL,
    wave_code TEXT NOT NULL,
    group_code TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    order_id TEXT NOT NULL,
    provider_trade_no TEXT NOT NULL,
    target_at TIMESTAMPTZ(6) NOT NULL,
    dispatch_started_at TIMESTAMPTZ(6) NOT NULL,
    dispatch_completed_at TIMESTAMPTZ(6) NOT NULL,
    dispatch_drift_micros BIGINT NOT NULL,
    http_status INTEGER NOT NULL,
    error_type TEXT
);

\copy membership_millisecond_boundary_all_scenarios FROM '__ALL_SCENARIO_ORDERS_CSV__' CSV HEADER
\copy membership_millisecond_boundary_all_dispatches FROM '__ALL_CALLBACK_DISPATCH_CSV__' CSV HEADER

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM membership_millisecond_boundary_all_scenarios) <> 40000
       OR (SELECT COUNT(DISTINCT user_id) FROM membership_millisecond_boundary_all_scenarios) <> 40000
       OR (SELECT COUNT(DISTINCT order_id) FROM membership_millisecond_boundary_all_scenarios) <> 40000 THEN
        RAISE EXCEPTION 'Final scenario manifest must contain 40,000 unique users and orders.';
    END IF;
    IF (SELECT COUNT(DISTINCT wave_code) FROM membership_millisecond_boundary_all_scenarios) <> 4
       OR (SELECT COUNT(DISTINCT group_code) FROM membership_millisecond_boundary_all_scenarios) <> 8 THEN
        RAISE EXCEPTION 'Final scenario manifest must contain four waves and eight groups.';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM membership_millisecond_boundary_all_scenarios
        GROUP BY group_code
        HAVING COUNT(*) <> 5000) THEN
        RAISE EXCEPTION 'Every boundary group must contain exactly 5,000 rows.';
    END IF;
    IF (SELECT COUNT(*) FROM (
            SELECT group_code, target_tier
            FROM membership_millisecond_boundary_all_scenarios
            GROUP BY group_code, target_tier) AS assignments) <> 32
       OR EXISTS (
            SELECT 1
            FROM membership_millisecond_boundary_all_scenarios
            GROUP BY group_code, target_tier
            HAVING COUNT(*) <> 1250) THEN
        RAISE EXCEPTION 'Every boundary group must contain 1,250 GO, PLUS, PRO and MAX orders.';
    END IF;
    IF (SELECT COUNT(*) FROM (
            SELECT group_code, target_offset_millis
            FROM membership_millisecond_boundary_all_scenarios
            GROUP BY group_code, target_offset_millis) AS offset_buckets) <> 2004
       OR EXISTS (
            SELECT 1
            FROM membership_millisecond_boundary_all_scenarios
            GROUP BY group_code, target_offset_millis
            HAVING NOT CASE
                WHEN group_code IN ('E-P1', 'H-P1')
                    THEN target_offset_millis = -1 AND COUNT(*) = 5000
                WHEN group_code IN ('E-A1', 'H-A1')
                    THEN target_offset_millis = 1 AND COUNT(*) = 5000
                WHEN group_code IN ('E-PR', 'H-PR')
                    THEN target_offset_millis BETWEEN -1000 AND -2
                         AND MOD(target_offset_millis, 2) = 0
                         AND COUNT(*) = 10
                WHEN group_code IN ('E-AR', 'H-AR')
                    THEN target_offset_millis BETWEEN 0 AND 998
                         AND MOD(target_offset_millis, 2) = 0
                         AND COUNT(*) = 10
                ELSE FALSE
            END) THEN
        RAISE EXCEPTION 'Boundary offset buckets do not match the repeated 500-point contract.';
    END IF;
    IF (SELECT COUNT(*) FROM (
            SELECT group_code, target_tier, target_offset_millis
            FROM membership_millisecond_boundary_all_scenarios
            GROUP BY group_code, target_tier, target_offset_millis) AS tier_offset_buckets) <> 8016
       OR EXISTS (
            SELECT 1
            FROM membership_millisecond_boundary_all_scenarios
            GROUP BY group_code, target_tier, target_offset_millis
            HAVING NOT CASE
                WHEN group_code IN ('E-P1', 'E-A1', 'H-P1', 'H-A1')
                    THEN COUNT(*) = 1250
                ELSE COUNT(*) BETWEEN 2 AND 3
            END) THEN
        RAISE EXCEPTION 'Every tier must preserve the exact repeated offset distribution.';
    END IF;
    IF (SELECT COUNT(*) FROM membership_millisecond_boundary_all_dispatches) <> 40000
       OR (SELECT COUNT(DISTINCT user_id) FROM membership_millisecond_boundary_all_dispatches) <> 40000
       OR (SELECT COUNT(DISTINCT order_id) FROM membership_millisecond_boundary_all_dispatches) <> 40000
       OR (SELECT COUNT(DISTINCT provider_trade_no) FROM membership_millisecond_boundary_all_dispatches) <> 40000 THEN
        RAISE EXCEPTION 'Final callback dispatch manifest must contain 40,000 unique rows.';
    END IF;
END
$$;

CREATE TEMP VIEW membership_millisecond_boundary_final_facts AS
SELECT test.*,
       dispatch.dispatch_started_at,
       dispatch.dispatch_completed_at,
       dispatch.dispatch_drift_micros,
       dispatch.provider_trade_no AS dispatch_provider_trade_no,
       dispatch.http_status,
       dispatch.error_type,
       payment_order.id AS internal_order_id,
       payment_order.login_identity_id AS actual_user_id,
       payment_order.status AS actual_status,
       payment_order.state_version,
       payment_order.payment_started_at,
       payment_order.expires_at,
       payment_order.closing_deadline_at,
       payment_order.paid_at AS order_paid_at,
       payment_order.entitlement_resolution,
       payment_order.entitlement_resolved_at,
       payment_order.created_at AS order_created_at,
       payment_order.updated_at AS order_updated_at,
       payment_order.provider_trade_no AS order_provider_trade_no,
       callback.id AS callback_id,
       callback.provider_trade_no AS callback_provider_trade_no,
       callback.paid_at AS callback_paid_at,
       callback.received_at,
       callback.resolution AS callback_resolution,
       callback.resolved_at AS callback_resolved_at,
       quota.membership_tier AS actual_membership_tier,
       CASE test.target_tier
           WHEN 'GO' THEN 1
           WHEN 'PLUS' THEN 4
           WHEN 'PRO' THEN 5
           WHEN 'MAX' THEN 6
       END AS expected_membership_tier,
       CASE
           WHEN callback.received_at < test.planned_hard_close_at THEN 'APPLIED'
           ELSE 'REFUND_REQUIRED'
       END AS expected_resolution,
       ROUND(EXTRACT(EPOCH FROM (callback.received_at - test.target_at)) * 1000000)::BIGINT
           AS server_target_drift_micros,
       ROUND(EXTRACT(EPOCH FROM (callback.received_at - payment_order.expires_at)) * 1000000)::BIGINT
           AS received_from_expires_micros,
       ROUND(EXTRACT(EPOCH FROM (callback.received_at - test.planned_hard_close_at)) * 1000000)::BIGINT
           AS received_from_hard_close_micros
FROM membership_millisecond_boundary_all_scenarios AS test
LEFT JOIN membership_millisecond_boundary_all_dispatches AS dispatch
  ON dispatch.order_id = test.order_id
 AND dispatch.user_id = test.user_id
LEFT JOIN membership_order AS payment_order
  ON public.hybrid_id_to_base64url(payment_order.id) = test.order_id
LEFT JOIN membership_payment_callback AS callback
  ON callback.order_id = payment_order.id
LEFT JOIN user_membership_quota AS quota
  ON quota.login_identity_id = test.user_id;

CREATE TEMP VIEW membership_millisecond_boundary_final_verdict AS
SELECT facts.*,
       CASE
           WHEN dispatch_started_at IS NULL OR dispatch_completed_at IS NULL THEN 'MISSING_DISPATCH'
           WHEN http_status <> 200 OR COALESCE(error_type, '') <> '' THEN 'CALLBACK_HTTP_FAILED'
           WHEN dispatch_drift_micros IS DISTINCT FROM
                ROUND(EXTRACT(EPOCH FROM (dispatch_started_at - target_at)) * 1000000)::BIGINT
               THEN 'DISPATCH_DRIFT_CHANGED'
           WHEN internal_order_id IS NULL THEN 'MISSING_ORDER'
           WHEN actual_user_id IS DISTINCT FROM user_id THEN 'ORDER_USER_MISMATCH'
           WHEN callback_id IS NULL THEN 'MISSING_CALLBACK'
           WHEN order_provider_trade_no IS NULL OR length(order_provider_trade_no) > 128
                OR order_provider_trade_no NOT LIKE group_code || '-MMB-%'
               THEN 'INVALID_PROVIDER_TRADE_PREFIX'
           WHEN callback_provider_trade_no IS DISTINCT FROM order_provider_trade_no
                OR order_provider_trade_no IS DISTINCT FROM dispatch_provider_trade_no
               THEN 'PROVIDER_TRADE_MISMATCH'
           WHEN state_version <= 0 THEN 'INVALID_STATE_VERSION'
           WHEN payment_started_at IS NULL OR expires_at IS NULL
                OR order_created_at IS NULL OR order_updated_at IS NULL
               THEN 'MISSING_ORDER_TIMESTAMP'
           WHEN callback_paid_at IS NULL OR received_at IS NULL OR callback_resolved_at IS NULL
               THEN 'MISSING_CALLBACK_TIMESTAMP'
           WHEN expires_at IS DISTINCT FROM planned_expires_at THEN 'EXPIRES_AT_CHANGED'
           WHEN closing_deadline_at IS NOT NULL
                AND closing_deadline_at IS DISTINCT FROM planned_hard_close_at
               THEN 'HARD_CLOSE_AT_CHANGED'
           WHEN callback_resolution IS DISTINCT FROM expected_resolution THEN 'CALLBACK_RESOLUTION_MISMATCH'
           WHEN entitlement_resolution IS DISTINCT FROM expected_resolution THEN 'ENTITLEMENT_RESOLUTION_MISMATCH'
           WHEN entitlement_resolved_at IS NULL THEN 'UNRESOLVED_FACT'
           WHEN expected_resolution = 'APPLIED' AND actual_status <> 2 THEN 'APPLIED_ORDER_NOT_PAID'
           WHEN expected_resolution = 'REFUND_REQUIRED' AND actual_status <> 4 THEN 'REFUND_ORDER_NOT_CLOSED'
           WHEN expected_resolution = 'APPLIED'
                AND actual_membership_tier IS DISTINCT FROM expected_membership_tier
               THEN 'MEMBERSHIP_NOT_GRANTED'
           WHEN expected_resolution = 'REFUND_REQUIRED' AND actual_membership_tier <> 0
               THEN 'REFUND_CHANGED_MEMBERSHIP'
           ELSE NULL
       END AS failure
FROM membership_millisecond_boundary_final_facts AS facts;

DO $$
DECLARE
    order_count BIGINT;
    callback_count BIGINT;
    unresolved_count BIGINT;
    failure_count BIGINT;
BEGIN
    SELECT COUNT(internal_order_id), COUNT(callback_id),
           COUNT(*) FILTER (
               WHERE entitlement_resolution IS NULL
                  OR entitlement_resolved_at IS NULL
                  OR callback_resolution IS NULL
                  OR callback_resolved_at IS NULL),
           COUNT(*) FILTER (WHERE failure IS NOT NULL)
      INTO order_count, callback_count, unresolved_count, failure_count
    FROM membership_millisecond_boundary_final_verdict;

    IF order_count <> 40000 OR callback_count <> 40000 OR unresolved_count <> 0 THEN
        RAISE EXCEPTION
            'Final boundary cardinality failed: orders=%, callbacks=%, unresolved=%',
            order_count, callback_count, unresolved_count;
    END IF;
    IF failure_count <> 0 THEN
        RAISE EXCEPTION 'Final microsecond verdict failed for % orders.', failure_count;
    END IF;
    IF (SELECT COUNT(*)
        FROM membership_order
        WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000039999) <> 40000 THEN
        RAISE EXCEPTION 'The fixed boundary users do not each own exactly this run order.';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM membership_order
        WHERE login_identity_id BETWEEN 70000000000000000 AND 70000000000039999
        GROUP BY login_identity_id
        HAVING COUNT(*) <> 1) THEN
        RAISE EXCEPTION 'A boundary user owns other than one order.';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM membership_payment_callback
        WHERE order_id IN (
            SELECT internal_order_id
            FROM membership_millisecond_boundary_final_verdict)
        GROUP BY order_id
        HAVING COUNT(*) <> 1) THEN
        RAISE EXCEPTION 'Final callback cardinality is invalid.';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM membership_millisecond_boundary_final_verdict
        WHERE actual_status IN (0, 1)
           OR entitlement_resolution IS NULL
           OR callback_resolution IS NULL) THEN
        RAISE EXCEPTION 'Final boundary scan found an active or unresolved order.';
    END IF;
END
$$;

\copy (SELECT run_id, wave_code, group_code, user_id, target_tier, order_id, order_provider_trade_no AS provider_trade_no, callback_provider_trade_no, to_char(payment_started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS payment_started_at, to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS expires_at, to_char(closing_deadline_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS closing_deadline_at, to_char(order_paid_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS order_paid_at, to_char(entitlement_resolved_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS entitlement_resolved_at, to_char(order_created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS order_created_at, to_char(order_updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS order_updated_at, to_char(callback_paid_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS callback_paid_at, to_char(received_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS received_at, to_char(callback_resolved_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS callback_resolved_at, to_char(target_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS target_at, to_char(dispatch_started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS dispatch_started_at, to_char(dispatch_completed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS dispatch_completed_at, dispatch_drift_micros, server_target_drift_micros, received_from_expires_micros, received_from_hard_close_micros, expected_resolution, callback_resolution, entitlement_resolution, actual_status FROM membership_millisecond_boundary_final_verdict ORDER BY user_id) TO '__FINAL_TIMESTAMP_EVIDENCE_CSV__' CSV HEADER

SELECT 'PASS' AS verdict;
