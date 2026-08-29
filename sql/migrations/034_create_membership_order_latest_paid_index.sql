-- 本文件只保留单条 CONCURRENTLY 语句，必须由迁移执行器以自动提交方式单独运行。
-- 查询把 PAID 固定为状态二，部分索引因此可以在 JDBC 泛化预编译计划下稳定参与规划。

CREATE INDEX CONCURRENTLY IF NOT EXISTS
    idx_membership_order_latest_paid
    ON membership_order (
        login_identity_id,
        membership_tier,
        paid_at DESC NULLS LAST,
        created_at DESC,
        id DESC
    )
    WHERE status = 2;
