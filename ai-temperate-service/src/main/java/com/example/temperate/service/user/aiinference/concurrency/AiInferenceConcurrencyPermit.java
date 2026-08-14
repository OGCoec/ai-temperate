package com.example.temperate.service.user.aiinference.concurrency;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 该租约是来标识全局、账号以及可选 API Key 三个 ZSET 中的同一加权 owner，便于原子续租和释放。
 */
public record AiInferenceConcurrencyPermit(
        HmacIdentifier accountIdentifier,
        HmacIdentifier apiKeyIdentifier,
        String owner,
        short weight) {

    public AiInferenceConcurrencyPermit {
        if (accountIdentifier == null || owner == null || owner.isBlank()
                || weight < 1 || weight > 10) {
            throw new IllegalArgumentException("AI inference concurrency permit is invalid");
        }
    }

    public boolean includesApiKey() {
        return apiKeyIdentifier != null;
    }
}
