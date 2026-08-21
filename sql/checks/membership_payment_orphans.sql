-- 检查会员订单指向不存在登录身份的孤儿数据；禁止物理外键后由运维巡检显式发现关系漂移。
SELECT
    'MEMBERSHIP_ORDER_WITHOUT_LOGIN_IDENTITY' AS issue_type,
    ENCODE(membership_order.id, 'hex') AS order_id_hex,
    membership_order.login_identity_id
FROM membership_order
LEFT JOIN userloginidentity
    ON userloginidentity.id = membership_order.login_identity_id
WHERE userloginidentity.id IS NULL;

-- 检查支付回调指向不存在会员订单的孤儿数据；删除订单前必须先保留或处理对应审计回调。
SELECT
    'PAYMENT_CALLBACK_WITHOUT_ORDER' AS issue_type,
    ENCODE(membership_payment_callback.id, 'hex') AS callback_id_hex,
    ENCODE(membership_payment_callback.order_id, 'hex') AS order_id_hex
FROM membership_payment_callback
LEFT JOIN membership_order
    ON membership_order.id = membership_payment_callback.order_id
WHERE membership_order.id IS NULL;
