BEGIN;

CREATE OR REPLACE FUNCTION public.hybrid_id_to_base64url(p_hybrid_id BYTEA)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
BEGIN
    IF OCTET_LENGTH(p_hybrid_id) <> 16 THEN
        RAISE EXCEPTION 'Hybrid ID must contain exactly 16 bytes'
            USING ERRCODE = '22023';
    END IF;

    RETURN RTRIM(
        TRANSLATE(ENCODE(p_hybrid_id, 'base64'), '+/', '-_'),
        '='
    );
END;
$$;

COMMENT ON FUNCTION public.hybrid_id_to_base64url(BYTEA) IS
    '把固定 16 字节 Hybrid ID 转换为 Java HybridBase64UrlCodec 相同的 22 字符无填充 Base64URL；仅用于排障查询和只读视图';

CREATE OR REPLACE VIEW public.membership_order_readable
WITH (security_invoker = true)
AS
SELECT
    public.hybrid_id_to_base64url(id) AS id_base64url,
    login_identity_id,
    membership_tier,
    pay_amount_yuan,
    pay_type,
    status,
    idempotency_key,
    provider_trade_no,
    payment_started_at,
    expires_at,
    closing_deadline_at,
    paid_at,
    state_version,
    created_at,
    updated_at
FROM public.membership_order;

COMMENT ON VIEW public.membership_order_readable IS
    '会员订单 Navicat 可读视图；把 BYTEA 主键显示为 22 字符 Base64URL，不替代业务表或资源授权';
COMMENT ON COLUMN public.membership_order_readable.id_base64url IS
    '与 API、Redis、RabbitMQ 和模拟支付 out_trade_no 完全相同的 22 字符会员订单公开 ID';

CREATE OR REPLACE VIEW public.membership_payment_callback_readable
WITH (security_invoker = true)
AS
SELECT
    public.hybrid_id_to_base64url(id) AS id_base64url,
    public.hybrid_id_to_base64url(order_id) AS order_id_base64url,
    provider_trade_no,
    trade_status,
    paid_amount_yuan,
    paid_at,
    received_at,
    resolution,
    resolved_at
FROM public.membership_payment_callback;

COMMENT ON VIEW public.membership_payment_callback_readable IS
    '会员支付回调 Navicat 可读视图；把回调与订单 BYTEA ID 显示为 22 字符 Base64URL，不替代回调审计表';
COMMENT ON COLUMN public.membership_payment_callback_readable.id_base64url IS
    '22 字符无填充 Base64URL 支付回调记录 ID';
COMMENT ON COLUMN public.membership_payment_callback_readable.order_id_base64url IS
    '可直接与 membership_order_readable.id_base64url 相等连接的 22 字符会员订单公开 ID';

COMMIT;
