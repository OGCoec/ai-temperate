package com.example.temperate.service.user.aiconversation.concurrency;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示跨实例全局和单用户并发集合中的加权租约；同一 owner 按 weight 展开为多个原子成员。
 */
public record AiConversationConcurrencyPermit(
        HmacIdentifier userIdentifier,
        String owner,
        short weight) {

    public AiConversationConcurrencyPermit {
        if (weight < 1 || weight > 10) {
            throw new IllegalArgumentException(
                    "AI concurrency permit weight must be between 1 and 10.");
        }
    }

    public AiConversationConcurrencyPermit(
            HmacIdentifier userIdentifier,
            String owner) {
        this(userIdentifier, owner, (short) 1);
    }
}
