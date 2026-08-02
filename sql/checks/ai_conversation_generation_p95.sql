-- psql 调用方必须通过 -v test_started_at='UTC ISO-8601' 限定本次隔离测试样本。
SELECT
    cancel_source,
    COUNT(*) AS samples,
    percentile_cont(0.95) WITHIN GROUP (
        ORDER BY EXTRACT(EPOCH FROM (settled_at - cancel_requested_at)) * 1000
    ) AS cancel_to_settle_p95_ms
FROM ai_conversation_generation
WHERE cancel_requested_at IS NOT NULL
  AND settled_at IS NOT NULL
  AND created_at >= :'test_started_at'::timestamptz
GROUP BY cancel_source
ORDER BY cancel_source;

-- 单独统计失联宽限和超时取消后的结算时延，避免把固定三十秒与资金事务耗时混为一个指标。
SELECT
    COUNT(*) AS samples,
    percentile_cont(0.95) WITHIN GROUP (
        ORDER BY EXTRACT(EPOCH FROM (cancel_requested_at - detached_at)) * 1000
    ) AS detach_to_cancel_p95_ms,
    percentile_cont(0.95) WITHIN GROUP (
        ORDER BY EXTRACT(EPOCH FROM (settled_at - cancel_requested_at)) * 1000
    ) AS cancel_to_settle_p95_ms
FROM ai_conversation_generation
WHERE cancel_source = 'CLIENT_EXIT_TIMEOUT'
  AND detached_at IS NOT NULL
  AND cancel_requested_at IS NOT NULL
  AND settled_at IS NOT NULL
  AND created_at >= :'test_started_at'::timestamptz;

-- 任一重复资金终态或未收敛状态都必须作为发布阻断项单独核对。
SELECT
    COUNT(*) FILTER (WHERE generation_status = 6) AS reconcile_required_count,
    COUNT(*) FILTER (
        WHERE generation_status IN (4, 5)
          AND settled_at IS NULL) AS terminal_without_settled_at_count
FROM ai_conversation_generation
WHERE created_at >= :'test_started_at'::timestamptz;
