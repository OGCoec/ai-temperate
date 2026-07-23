-- 检查缺少对应登录身份的会员额度记录；结果集中的每一行都是待处置的孤儿记录。
SELECT
    umq.id AS user_membership_quota_id,
    umq.login_identity_id
FROM user_membership_quota umq
LEFT JOIN userloginidentity uli
    ON uli.id = umq.login_identity_id
WHERE uli.id IS NULL;
