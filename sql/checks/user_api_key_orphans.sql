-- 检查缺少对应登录身份的 API Key 记录；结果集中的每一行都是待处置的孤儿记录。
SELECT
    uak.id AS user_api_key_id,
    uak.login_identity_id,
    uak.status,
    uak.created_at
FROM user_api_key uak
LEFT JOIN userloginidentity uli
    ON uli.id = uak.login_identity_id
WHERE uli.id IS NULL;
