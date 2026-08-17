package com.example.temperate.service.user.apikey.idempotency;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 该服务是来用同一用户与 UUIDv4 创建意图合并短期并发请求；它只负责辅助削峰，最终幂等仍由 PostgreSQL 唯一索引裁决。
 */
public interface ApiKeyCreateLockService {

    /**
     * 在辅助锁内执行数据库幂等路径；Redis 异常时实现可以安全降级到仍受唯一索引保护的操作。
     */
    <T> T execute(long loginIdentityId, UUID idempotencyKey, Supplier<T> action);
}
