package com.example.temperate.service.user.membership.payment.order;

import java.time.OffsetDateTime;

/**
 * 该服务是来把支付发起的条件更新、并发重放解析和资源归属校验收敛在单个 PostgreSQL 本地事务中。
 */
public interface MembershipPaymentAttemptTransactionService {

    MembershipPaymentAttemptDatabaseResult startOrGet(
            long loginIdentityId,
            byte[] orderId,
            OffsetDateTime attemptedAt);
}
