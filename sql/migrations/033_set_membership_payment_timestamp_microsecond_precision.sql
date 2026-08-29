BEGIN;

-- PostgreSQL 不允许在普通视图仍依赖列时改变列类型；两个只读视图在同一事务内按原定义重建。
DROP VIEW IF EXISTS public.membership_payment_callback_readable;
DROP VIEW IF EXISTS public.membership_order_readable;

-- 会员订单的全部业务时间显式固定为 PostgreSQL 微秒精度，防止未来 DDL 默认值变化造成契约漂移。
ALTER TABLE membership_order
    ALTER COLUMN payment_started_at TYPE TIMESTAMPTZ(6),
    ALTER COLUMN expires_at TYPE TIMESTAMPTZ(6),
    ALTER COLUMN closing_deadline_at TYPE TIMESTAMPTZ(6),
    ALTER COLUMN paid_at TYPE TIMESTAMPTZ(6),
    ALTER COLUMN entitlement_resolved_at TYPE TIMESTAMPTZ(6),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(6),
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(6);

-- 回调事实和裁决时间使用同一微秒边界，保证硬关闭比较与审计证据可以逐笔复现。
ALTER TABLE membership_payment_callback
    ALTER COLUMN paid_at TYPE TIMESTAMPTZ(6),
    ALTER COLUMN received_at TYPE TIMESTAMPTZ(6),
    ALTER COLUMN resolved_at TYPE TIMESTAMPTZ(6);

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
    entitlement_resolution,
    entitlement_resolved_at,
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
