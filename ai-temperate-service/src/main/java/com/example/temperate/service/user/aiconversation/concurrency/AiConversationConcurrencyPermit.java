package com.example.temperate.service.user.aiconversation.concurrency;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示跨实例全局和单用户并发集合中同一个随机租约成员。
 */
public record AiConversationConcurrencyPermit(
        HmacIdentifier userIdentifier,
        String owner) {
}
