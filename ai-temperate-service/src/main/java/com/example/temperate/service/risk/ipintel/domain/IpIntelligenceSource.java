package com.example.temperate.service.risk.ipintel.domain;

/**
 * 标识标准化 IP 情报的最终来源，供缓存期限、指标和脱敏审计使用。
 */
public enum IpIntelligenceSource {
    IP2LOCATION,
    IPING,
    IP2LOCATION_AND_IPING,
    LOCAL_BIN,
    DEFAULT
}
