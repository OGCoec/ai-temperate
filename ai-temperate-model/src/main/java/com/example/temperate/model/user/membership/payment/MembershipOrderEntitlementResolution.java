package com.example.temperate.model.user.membership.payment;

/**
 * 该枚举是来记录一笔已支付会员订单是否已经发放权益、需要退款，或仅属于不自动补发的历史事实。
 *
 * <p>它只描述 PostgreSQL 内部权益裁决，不进入公开订单 JSON、Redis 快照或 RabbitMQ 消息。</p>
 */
public enum MembershipOrderEntitlementResolution {
    APPLIED,
    NOT_GRANTED,
    REFUND_REQUIRED,
    LEGACY_NOT_GRANTED
}
