\set ON_ERROR_STOP on

CREATE TEMP TABLE membership_workbook_scope (
    scope_ordinal BIGINT NOT NULL,
    group_ordinal INTEGER NOT NULL,
    run_id TEXT NOT NULL,
    wave_code TEXT NOT NULL,
    group_code TEXT NOT NULL,
    trace_id TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    target_tier TEXT NOT NULL,
    order_id_base64url TEXT NOT NULL,
    planned_expires_at TIMESTAMPTZ(6) NOT NULL,
    planned_hard_close_at TIMESTAMPTZ(6) NOT NULL,
    target_offset_millis BIGINT NOT NULL,
    target_at TIMESTAMPTZ(6) NOT NULL
);

\copy membership_workbook_scope FROM '__SCOPE_CSV__' CSV HEADER

CREATE TEMP VIEW membership_workbook_facts AS
SELECT scope.scope_ordinal,
       scope.group_ordinal,
       scope.run_id AS source_run_id,
       scope.wave_code,
       scope.group_code,
       scope.trace_id,
       scope.user_id,
       scope.target_tier,
       scope.order_id_base64url AS expected_order_id_base64url,
       scope.planned_expires_at,
       scope.planned_hard_close_at,
       scope.target_offset_millis,
       scope.target_at,
       payment_order.id AS order_id_raw,
       public.hybrid_id_to_base64url(payment_order.id) AS order_id_base64url,
       payment_order.login_identity_id,
       payment_order.membership_tier AS order_membership_tier,
       payment_order.pay_amount_yuan,
       payment_order.pay_type,
       payment_order.status AS order_status,
       payment_order.idempotency_key,
       payment_order.provider_trade_no AS order_provider_trade_no,
       payment_order.payment_started_at,
       payment_order.expires_at,
       payment_order.closing_deadline_at,
       payment_order.paid_at AS order_paid_at,
       payment_order.entitlement_resolution,
       payment_order.entitlement_resolved_at,
       payment_order.state_version,
       payment_order.created_at AS order_created_at,
       payment_order.updated_at AS order_updated_at,
       callback.id AS callback_id_raw,
       public.hybrid_id_to_base64url(callback.id) AS callback_id_base64url,
       callback.order_id AS callback_order_id_raw,
       public.hybrid_id_to_base64url(callback.order_id)
           AS callback_order_id_base64url,
       callback.provider_trade_no AS callback_provider_trade_no,
       callback.trade_status,
       callback.paid_amount_yuan,
       callback.paid_at AS callback_paid_at,
       callback.received_at,
       callback.resolution AS callback_resolution,
       callback.resolved_at AS callback_resolved_at,
       quota.id AS quota_id,
       quota.login_identity_id AS quota_login_identity_id,
       quota.membership_tier AS quota_membership_tier,
       quota.quota_balance_minor,
       quota.quota_period_started_at,
       quota.quota_period_ends_at,
       quota.membership_expires_at,
       CASE scope.target_tier
           WHEN 'GO' THEN 1
           WHEN 'EDU' THEN 2
           WHEN 'TEAM' THEN 3
           WHEN 'PLUS' THEN 4
           WHEN 'PRO' THEN 5
           WHEN 'MAX' THEN 6
           ELSE NULL
       END AS expected_membership_tier,
       CASE
           WHEN callback.received_at < scope.planned_hard_close_at THEN 'APPLIED'
           WHEN callback.received_at IS NOT NULL THEN 'REFUND_REQUIRED'
           ELSE NULL
       END AS expected_resolution
FROM membership_workbook_scope AS scope
LEFT JOIN membership_order AS payment_order
  ON public.hybrid_id_to_base64url(payment_order.id)
       = scope.order_id_base64url
LEFT JOIN membership_payment_callback AS callback
  ON callback.order_id = payment_order.id
LEFT JOIN user_membership_quota AS quota
  ON quota.login_identity_id = scope.user_id;

CREATE TEMP VIEW membership_workbook_consistency AS
SELECT facts.*,
       CASE
           WHEN order_id_raw IS NULL THEN 'MISSING_ORDER'
           WHEN login_identity_id IS DISTINCT FROM user_id THEN 'ORDER_USER_MISMATCH'
           WHEN callback_id_raw IS NULL THEN 'MISSING_CALLBACK'
           WHEN callback_order_id_raw IS DISTINCT FROM order_id_raw
               THEN 'CALLBACK_ORDER_MISMATCH'
           WHEN quota_id IS NULL THEN 'MISSING_QUOTA'
           WHEN quota_login_identity_id IS DISTINCT FROM user_id
               THEN 'QUOTA_USER_MISMATCH'
           WHEN expected_membership_tier IS NULL THEN 'UNKNOWN_TARGET_TIER'
           WHEN callback_resolution NOT IN ('APPLIED', 'REFUND_REQUIRED')
               THEN 'NON_TERMINAL_CALLBACK_RESOLUTION'
           WHEN group_code NOT IN ('H-P1', 'H-PR')
                AND callback_resolution IS DISTINCT FROM expected_resolution
               THEN 'CALLBACK_RESOLUTION_MISMATCH'
           WHEN entitlement_resolution IS DISTINCT FROM callback_resolution
               THEN 'ENTITLEMENT_RESOLUTION_MISMATCH'
           WHEN callback_resolution = 'APPLIED'
                AND order_provider_trade_no IS DISTINCT FROM callback_provider_trade_no
               THEN 'PROVIDER_TRADE_MISMATCH'
           WHEN callback_resolution = 'REFUND_REQUIRED'
                AND order_provider_trade_no IS NOT NULL
               THEN 'REFUND_PROVIDER_TRADE_NOT_CLEARED'
           WHEN callback_resolved_at IS NULL OR entitlement_resolved_at IS NULL
               THEN 'UNRESOLVED_FACT'
           WHEN callback_resolution = 'APPLIED' AND order_status <> 2
               THEN 'APPLIED_ORDER_NOT_PAID'
           WHEN callback_resolution = 'REFUND_REQUIRED' AND order_status <> 4
               THEN 'REFUND_ORDER_NOT_CLOSED'
           WHEN callback_resolution = 'APPLIED'
                AND quota_membership_tier IS DISTINCT FROM expected_membership_tier
               THEN 'MEMBERSHIP_NOT_GRANTED'
           WHEN callback_resolution = 'REFUND_REQUIRED'
                AND quota_membership_tier IS DISTINCT FROM 0
               THEN 'REFUND_CHANGED_MEMBERSHIP'
           ELSE NULL
       END AS failure
FROM membership_workbook_facts AS facts;

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

SELECT COUNT(*) = __EXPECTED_TOTAL__ AS scope_count_ok
FROM membership_workbook_scope
\gset
\if :scope_count_ok
\else
    \echo 'membership workbook scope row count mismatch'
    \quit 3
\endif

SELECT COUNT(DISTINCT user_id) = __EXPECTED_TOTAL__
       AND COUNT(DISTINCT order_id_base64url) = __EXPECTED_TOTAL__
           AS scope_uniqueness_ok
FROM membership_workbook_scope
\gset
\if :scope_uniqueness_ok
\else
    \echo 'membership workbook scope contains duplicate users or orders'
    \quit 3
\endif

\copy (SELECT scope_ordinal, group_ordinal, source_run_id, wave_code, group_code, trace_id, order_id_base64url, encode(order_id_raw, 'hex') AS order_id_raw_hex, login_identity_id::TEXT AS login_identity_id_raw, order_membership_tier::TEXT AS membership_tier_code, pay_amount_yuan::TEXT AS pay_amount_yuan, pay_type, order_status::TEXT AS status_code, idempotency_key::TEXT AS idempotency_key, order_provider_trade_no AS provider_trade_no, to_char(payment_started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS payment_started_at_utc, to_char(expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS expires_at_utc, to_char(closing_deadline_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS closing_deadline_at_utc, to_char(order_paid_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS paid_at_utc, entitlement_resolution, to_char(entitlement_resolved_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS entitlement_resolved_at_utc, state_version::TEXT AS state_version_raw, to_char(order_created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS created_at_utc, to_char(order_updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS updated_at_utc FROM membership_workbook_facts ORDER BY group_ordinal, user_id) TO '__ORDERS_CSV__' CSV HEADER

\copy (SELECT scope_ordinal, group_ordinal, source_run_id, wave_code, group_code, trace_id, callback_id_base64url, encode(callback_id_raw, 'hex') AS callback_id_raw_hex, callback_order_id_base64url AS order_id_base64url, encode(callback_order_id_raw, 'hex') AS order_id_raw_hex, callback_provider_trade_no AS provider_trade_no, trade_status, paid_amount_yuan::TEXT AS paid_amount_yuan, to_char(callback_paid_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS paid_at_utc, to_char(received_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS received_at_utc, callback_resolution AS resolution, to_char(callback_resolved_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS resolved_at_utc FROM membership_workbook_facts ORDER BY group_ordinal, user_id) TO '__CALLBACKS_CSV__' CSV HEADER

\copy (SELECT scope_ordinal, group_ordinal, source_run_id, wave_code, group_code, trace_id, quota_id::TEXT AS quota_id_raw, quota_login_identity_id::TEXT AS login_identity_id_raw, quota_membership_tier::TEXT AS membership_tier_code, quota_balance_minor::TEXT AS quota_balance_minor_raw, to_char(quota_period_started_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS quota_period_started_at_utc, to_char(quota_period_ends_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS quota_period_ends_at_utc, to_char(membership_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS membership_expires_at_utc FROM membership_workbook_facts ORDER BY group_ordinal, user_id) TO '__QUOTAS_CSV__' CSV HEADER

\copy (SELECT scope_ordinal, group_ordinal, source_run_id, group_code, order_id_base64url AS membership_order_id_base64url, encode(order_id_raw, 'hex') AS membership_order_id_raw_hex, callback_id_base64url AS membership_payment_callback_id_base64url, encode(callback_id_raw, 'hex') AS membership_payment_callback_id_raw_hex, quota_id::TEXT AS user_membership_quota_id_raw, user_id::TEXT AS login_identity_id_raw FROM membership_workbook_facts ORDER BY group_ordinal, user_id) TO '__ID_MAPPING_CSV__' CSV HEADER

\copy (SELECT scope_ordinal, group_ordinal, source_run_id, group_code, user_id::TEXT AS user_id_raw, target_tier, expected_order_id_base64url, order_id_base64url, callback_id_base64url, quota_id::TEXT AS quota_id_raw, expected_membership_tier::TEXT AS expected_membership_tier_code, quota_membership_tier::TEXT AS actual_membership_tier_code, expected_resolution, callback_resolution, entitlement_resolution, order_status::TEXT AS order_status_code, failure FROM membership_workbook_consistency ORDER BY group_ordinal, user_id) TO '__CONSISTENCY_CSV__' CSV HEADER

\copy (SELECT __EXPECTED_TOTAL__::BIGINT AS expected_records, COUNT(order_id_raw)::BIGINT AS order_count, COUNT(callback_id_raw)::BIGINT AS callback_count, COUNT(quota_id)::BIGINT AS quota_count, COUNT(DISTINCT order_id_raw)::BIGINT AS distinct_order_count, COUNT(DISTINCT callback_id_raw)::BIGINT AS distinct_callback_count, COUNT(DISTINCT user_id)::BIGINT AS distinct_user_count, COUNT(DISTINCT quota_id)::BIGINT AS distinct_quota_count, COUNT(*) FILTER (WHERE order_id_raw IS NULL)::BIGINT AS missing_order_count, COUNT(*) FILTER (WHERE callback_id_raw IS NULL)::BIGINT AS missing_callback_count, COUNT(*) FILTER (WHERE quota_id IS NULL)::BIGINT AS missing_quota_count, (COUNT(order_id_raw) - COUNT(DISTINCT order_id_raw))::BIGINT AS duplicate_order_count, (COUNT(callback_id_raw) - COUNT(DISTINCT callback_id_raw))::BIGINT AS duplicate_callback_count, (COUNT(quota_id) - COUNT(DISTINCT quota_id))::BIGINT AS duplicate_quota_count, COUNT(*) FILTER (WHERE callback_order_id_raw IS DISTINCT FROM order_id_raw AND callback_id_raw IS NOT NULL)::BIGINT AS callback_order_mismatch_count, COUNT(*) FILTER (WHERE (callback_resolution = 'APPLIED' AND order_provider_trade_no IS DISTINCT FROM callback_provider_trade_no) OR (callback_resolution = 'REFUND_REQUIRED' AND order_provider_trade_no IS NOT NULL))::BIGINT AS provider_trade_mismatch_count, COUNT(*) FILTER (WHERE (group_code NOT IN ('H-P1', 'H-PR') AND callback_resolution IS DISTINCT FROM expected_resolution) OR (callback_resolution IS NOT NULL AND entitlement_resolution IS DISTINCT FROM callback_resolution))::BIGINT AS resolution_mismatch_count, COUNT(*) FILTER (WHERE (callback_resolution = 'APPLIED' AND quota_membership_tier IS DISTINCT FROM expected_membership_tier) OR (callback_resolution = 'REFUND_REQUIRED' AND quota_membership_tier IS DISTINCT FROM 0))::BIGINT AS membership_tier_mismatch_count, COUNT(*) FILTER (WHERE callback_resolved_at IS NULL OR entitlement_resolved_at IS NULL)::BIGINT AS unresolved_fact_count, COUNT(*) FILTER (WHERE failure IS NOT NULL)::BIGINT AS consistency_failure_count FROM membership_workbook_consistency) TO '__METRICS_CSV__' CSV HEADER

ROLLBACK;
