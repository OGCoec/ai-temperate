-- 本文件只保留单条 CONCURRENTLY 语句，必须由迁移执行器以自动提交方式单独运行。
-- 重复数据已由 030 迁移预检；若两份迁移之间又产生竞态重复，唯一索引创建会安全失败且不删除任何订单。

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS
    uk_membership_order_single_active_identity
    ON membership_order (login_identity_id)
    WHERE status IN (0, 1)
       OR (status = 2 AND entitlement_resolution IS NULL);
