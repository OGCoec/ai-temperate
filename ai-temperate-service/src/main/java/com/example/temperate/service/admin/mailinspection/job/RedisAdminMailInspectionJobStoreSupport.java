package com.example.temperate.service.admin.mailinspection.job;

/**
 * 提供 Redis 邮件任务结果分桶的无 I/O 边界计算，供 Store 与契约测试共享。
 */
public final class RedisAdminMailInspectionJobStoreSupport {

    private RedisAdminMailInspectionJobStoreSupport() {
    }

    public static int bucketForLine(int lineNumber, int bucketSize) {
        if (lineNumber < 1 || bucketSize < 1) {
            throw new IllegalArgumentException(
                    "line number and bucket size must be positive");
        }
        return (lineNumber - 1) / bucketSize;
    }

    public static int bucketCount(int lineCount, int bucketSize) {
        if (lineCount < 0 || bucketSize < 1) {
            throw new IllegalArgumentException(
                    "line count must be non-negative and bucket size positive");
        }
        return lineCount == 0 ? 0 : ((lineCount - 1) / bucketSize) + 1;
    }
}
