package com.example.temperate.service.risk.ipintel.domain;

import java.math.BigDecimal;

/**
 * 保存经过边界校验和分值归一化的 IP 情报快照，供实时评分使用。
 *
 * <p>对象不包含明文 IP 或第三方原始响应；缺失字段使用 null 表示，禁止用虚假坐标参与不可能旅行计算。</p>
 */
public record IpIntelligenceSnapshot(
        int schemaVersion,
        int trustScore,
        String countryCode,
        Long asn,
        BigDecimal latitude,
        BigDecimal longitude,
        NetworkType networkType,
        boolean scoreIncludesNetworkRisk,
        IpIntelligenceSource source) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public IpIntelligenceSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported IP intelligence schema.");
        }
        if (trustScore < 0 || trustScore > 100) {
            throw new IllegalArgumentException("IP trust score must be between 0 and 100.");
        }
        if (networkType == null || source == null) {
            throw new IllegalArgumentException("IP intelligence metadata must not be null.");
        }
    }

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
