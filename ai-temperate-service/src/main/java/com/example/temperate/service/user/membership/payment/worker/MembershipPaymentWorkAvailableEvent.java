package com.example.temperate.service.user.membership.payment.worker;

import java.util.Objects;

/**
 * 该进程内事件是来降低 Redis ZSET 新工作到 Worker 启动之间的固定轮询等待；Redis 仍是唯一工作事实来源。
 */
public record MembershipPaymentWorkAvailableEvent(MembershipPaymentWorkType type) {

    public MembershipPaymentWorkAvailableEvent {
        type = Objects.requireNonNull(type, "type must not be null");
    }
}
