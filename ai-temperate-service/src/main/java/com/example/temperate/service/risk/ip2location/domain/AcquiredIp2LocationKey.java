package com.example.temperate.service.risk.ip2location.domain;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示已经原子扣减一次本地额度、可供单次外部请求使用的解密凭据。
 */
public record AcquiredIp2LocationKey(
        HmacIdentifier keyId,
        String apiKey,
        long remainingQuota) {

    @Override
    public String toString() {
        return "AcquiredIp2LocationKey[redacted]";
    }
}
