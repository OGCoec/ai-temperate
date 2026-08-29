package com.example.temperate.service.user.membership.payment.observability;

/**
 * 该枚举是来定义状态机内部可聚合的固定耗时步骤，使Redis、数据库、Marker、BAR和Rabbit发布开销可以独立比较。
 */
public enum MembershipPaymentTimingStep {
    REDIS_ORDER_READ,
    REDIS_ORDER_WRITE,
    REDIS_PROVIDER_WRITE,
    DATABASE_CALL,
    MARKER_READ,
    BAR_SIGNATURE,
    BAR_CREATE,
    BAR_QUERY,
    BAR_CLOSE,
    BAR_REFUND,
    REDIS_STATE_TRANSITION,
    RABBIT_PUBLISH_CONFIRM,
    RABBIT_ACK,
    CALLBACK_ENQUEUE,
    CALLBACK_CLAIM,
    CALLBACK_READ,
    CALLBACK_REQUEUE,
    CALLBACK_COMPLETE,
    OTHER_REDIS
}
