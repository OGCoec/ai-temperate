package com.example.temperate.service.risk.preauth.domain;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 保存一次已经完成信用评分和地理合并的 PreAuth 网络快照，供单 Hash 原子状态转换使用。
 *
 * <p>该对象只包含 IP 的 HMAC 摘要，不包含明文 IP 或第三方原始响应；缓存有效期完全由 Redis
 * Key 的存在性决定，因此这里只保留当前可信边缘请求的观察时间。</p>
 */
public record PreAuthNetworkSnapshot(
        HmacIdentifier ipDigest,
        int trustScore,
        String countryCode,
        Long asn,
        BigDecimal latitude,
        BigDecimal longitude,
        NetworkType networkType,
        boolean scoreIncludesNetworkRisk,
        PreAuthRiskSource riskSource,
        PreAuthGeoSource geoSource,
        Instant observedAt) {

    public PreAuthNetworkSnapshot {
        if (ipDigest == null
                || trustScore < 0
                || trustScore > 100
                || networkType == null
                || riskSource == null
                || geoSource == null
                || observedAt == null) {
            throw new IllegalArgumentException("PreAuth network snapshot is invalid.");
        }
    }
}
