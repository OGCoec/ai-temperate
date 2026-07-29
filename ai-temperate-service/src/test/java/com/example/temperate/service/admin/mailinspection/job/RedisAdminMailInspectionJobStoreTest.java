package com.example.temperate.service.admin.mailinspection.job;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁定 Redis 邮件任务分桶、滑动租约和终态保留的纯计算边界，避免测试依赖外部 Redis。
 */
final class RedisAdminMailInspectionJobStoreTest {

    @Test
    void calculatesBoundedResultBucketsFromConfiguredLineLimit() {
        AdminMailInspectionProperties.Job job =
                AdminMailInspectionProperties.defaults().job();

        assertEquals(313, RedisAdminMailInspectionJobStoreSupport.bucketCount(
                job.maxCredentialLines(), job.resultBucketSize()));
        assertEquals(0, RedisAdminMailInspectionJobStoreSupport.bucketForLine(1, 32));
        assertEquals(1, RedisAdminMailInspectionJobStoreSupport.bucketForLine(33, 32));
    }
}
