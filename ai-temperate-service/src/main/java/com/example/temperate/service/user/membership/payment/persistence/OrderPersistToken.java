package com.example.temperate.service.user.membership.payment.persistence;

import com.example.temperate.common.redis.key.MembershipOrderRedisId;

/**
 * 该令牌是来唯一标识一版待持久化订单快照，并绑定 processing ZSet 领取时间以隔离超时旧 Worker。
 */
public record OrderPersistToken(
        String orderId,
        long stateVersion,
        long claimedAtEpochMillis) {

    private static final char DELIMITER = '#';

    public OrderPersistToken {
        new MembershipOrderRedisId(orderId);
        if (stateVersion <= 0 || claimedAtEpochMillis <= 0) {
            throw new IllegalArgumentException(
                    "Order persistence version and claim time must be positive.");
        }
    }

    public String member() {
        return orderId + DELIMITER + stateVersion;
    }

    /** 从有界 ZSet member 恢复订单和版本，并附加当前这次领取的精确分值。 */
    public static OrderPersistToken claimed(String member, long claimedAtEpochMillis) {
        int separator = member == null ? -1 : member.lastIndexOf(DELIMITER);
        if (separator <= 0 || separator == member.length() - 1) {
            throw new IllegalArgumentException("Order persistence token is malformed.");
        }
        long version;
        try {
            version = Long.parseLong(member.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Order persistence token version is malformed.", exception);
        }
        return new OrderPersistToken(
                member.substring(0, separator), version, claimedAtEpochMillis);
    }
}
