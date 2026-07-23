-- 检查缺少对应登录身份的用户资料；结果集中的每一行都是待处置的孤儿记录。
SELECT
    up.id AS user_profile_id,
    up.login_identity_id
FROM user_profile up
LEFT JOIN userloginidentity uli
    ON uli.id = up.login_identity_id
WHERE uli.id IS NULL
ORDER BY up.id;
