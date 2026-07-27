package com.example.temperate.service.risk.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 表示当前请求经过可信来源解析后的 IP、国家、ASN、坐标与观测时间。
 *
 * <p>明文 IP 仅在单次请求与无缓存失败响应内使用；作为网络身份写入 Redis 时必须转换为 HMAC 摘要，
 * WebRTC 失败详情如需进入同一个 PreAuth Hash，则必须使用独立 AES-GCM 密钥整体加密。</p>
 */
public record TrustedNetworkObservation(
        String clientIp,
        String countryCode,
        Long asn,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant observedAt) {

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
