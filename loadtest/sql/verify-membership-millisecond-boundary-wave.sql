\set ON_ERROR_STOP on

CREATE TEMP TABLE membership_millisecond_boundary_scope (
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

\copy membership_millisecond_boundary_scope FROM '__SCENARIO_ORDERS_CSV__' CSV HEADER

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM membership_millisecond_boundary_scope) <> 5000 THEN
        RAISE EXCEPTION 'One boundary segment must contain exactly 5,000 evidence rows.';
    END IF;
    IF (SELECT COUNT(DISTINCT user_id) FROM membership_millisecond_boundary_scope) <> 5000
       OR (SELECT COUNT(DISTINCT order_id) FROM membership_millisecond_boundary_scope) <> 5000 THEN
        RAISE EXCEPTION 'Boundary evidence contains duplicate users or orders.';
    END IF;
END
$$;

CREATE TEMP VIEW membership_millisecond_boundary_facts AS
SELECT test.*,
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
FROM membership_millisecond_boundary_scope AS test
LEFT JOIN membership_order AS payment_order
  ON public.hybrid_id_to_base64url(payment_order.id) = test.order_id
LEFT JOIN membership_payment_callback AS callback
  ON callback.order_id = payment_order.id
LEFT JOIN user_membership_quota AS quota
  ON quota.login_identity_id = test.user_id;

CREATE TEMP VIEW membership_millisecond_boundary_verdict AS
SELECT facts.*,
       CASE
           WHEN internal_order_id IS NULL THEN 'MISSING_ORDER'
           WHEN actual_user_id IS DISTINCT FROM user_id THEN 'ORDER_USER_MISMATCH'
           WHEN callback_id IS NULL THEN 'MISSING_CALLBACK'
           WHEN order_provider_trade_no IS NULL OR length(order_provider_trade_no) > 128
                OR order_provider_trade_no NOT LIKE group_code || '-MMB-%'
               THEN 'INVALID_PROVIDER_TRADE_PREFIX'
           WHEN callback_provider_trade_no IS DISTINCT FROM order_provider_trade_no
               THEN 'PROVIDER_TRADE_MISMATCH'
           WHEN state_version <= 0 THEN 'INVALID_STATE_VERSION'
           WHEN expires_at IS DISTINCT FROM planned_expires_at THEN 'EXPIRES_AT_CHANGED'
           -- 未进入 CLOSING 就完成支付时 deadline 可以为空；只裁决已经写入的 deadline 是否准确。
           WHEN closing_deadline_at IS NOT NULL
                AND closing_deadline_at IS DISTINCT FROM planned_hard_close_at
               THEN 'HARD_CLOSE_AT_CHANGED'
           WHEN callback_resolution IS DISTINCT FROM expected_resolution THEN 'CALLBACK_RESOLUTION_MISMATCH'
           WHEN entitlement_resolution IS DISTINCT FROM expected_resolution THEN 'ENTITLEMENT_RESOLUTION_MISMATCH'
           WHEN entitlement_resolved_at IS NULL OR callback_resolved_at IS NULL THEN 'UNRESOLVED_FACT'
           WHEN expected_resolution = 'APPLIED' AND actual_status <> 2 THEN 'APPLIED_ORDER_NOT_PAID'
           WHEN expected_resolution = 'REFUND_REQUIRED' AND actual_status <> 4 THEN 'REFUND_ORDER_NOT_CLOSED'
           WHEN expected_resolution = 'APPLIED'
                AND actual_membership_tier IS DISTINCT FROM expected_membership_tier
               THEN 'MEMBERSHIP_NOT_GRANTED'
           WHEN expected_resolution = 'REFUND_REQUIRED' AND actual_membership_tier <> 0
               THEN 'REFUND_CHANGED_MEMBERSHIP'
           ELSE NULL
       END AS failure
FROM membership_millisecond_boundary_facts AS facts;

DO $$
DECLARE
    callback_count BIGINT;
    failure_count BIGINT;
BEGIN
    SELECT COUNT(callback_id), COUNT(*) FILTER (WHERE failure IS NOT NULL)
      INTO callback_count, failure_count
    FROM membership_millisecond_boundary_verdict;
    IF callback_count <> 5000 THEN
        RAISE EXCEPTION 'Boundary segment callback cardinality is invalid: %', callback_count;
    END IF;
    IF EXISTS (
        SELECT 1
        FROM membership_payment_callback AS callback
        WHERE callback.order_id IN (
            SELECT internal_order_id
            FROM membership_millisecond_boundary_verdict)
        GROUP BY callback.order_id
        HAVING COUNT(*) <> 1) THEN
        RAISE EXCEPTION 'A boundary order owns more than one callback.';
    END IF;
    IF failure_count <> 0 THEN
        RAISE EXCEPTION 'Boundary server-time verdict failed for % orders.', failure_count;
    END IF;
END
$$;

\copy (SELECT run_id, wave_code, group_code, user_id, target_tier, order_id, order_provider_trade_no, callback_provider_trade_no, target_offset_millis, to_char(target_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS target_at, to_char(payment_started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS payment_started_at, to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS expires_at, to_char(closing_deadline_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS closing_deadline_at, to_char(order_paid_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS order_paid_at, to_char(entitlement_resolved_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS entitlement_resolved_at, to_char(order_created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS order_created_at, to_char(order_updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS order_updated_at, to_char(callback_paid_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS callback_paid_at, to_char(received_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS received_at, to_char(callback_resolved_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS callback_resolved_at, server_target_drift_micros, received_from_expires_micros, received_from_hard_close_micros, expected_resolution, callback_resolution, entitlement_resolution, actual_status, failure FROM membership_millisecond_boundary_verdict ORDER BY user_id) TO '__SERVER_VERDICT_CSV__' CSV HEADER

SELECT 'PASS' AS verdict;
