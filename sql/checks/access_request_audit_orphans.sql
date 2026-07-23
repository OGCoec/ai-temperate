-- 该查询识别已找不到登录身份的历史审计记录；这些记录按安全保留期继续保存，不自动删除。
SELECT
    audit.user_id,
    COUNT(*) AS orphan_audit_count,
    MIN(audit.occurred_at) AS first_occurred_at,
    MAX(audit.occurred_at) AS last_occurred_at
FROM access_request_audit audit
LEFT JOIN userloginidentity identity ON identity.id = audit.user_id
WHERE audit.user_id IS NOT NULL
  AND identity.id IS NULL
GROUP BY audit.user_id
ORDER BY audit.user_id;
