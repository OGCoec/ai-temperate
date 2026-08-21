\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE cleanup_orders (idempotency_key UUID NOT NULL);
\copy cleanup_orders FROM :'scenario_csv' CSV HEADER
DELETE FROM membership_payment_callback c
USING membership_order o, cleanup_orders x
WHERE c.order_id = o.id AND o.idempotency_key = x.idempotency_key;
DELETE FROM membership_order o
USING cleanup_orders x
WHERE o.idempotency_key = x.idempotency_key;
COMMIT;
