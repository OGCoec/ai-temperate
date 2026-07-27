package com.example.temperate.service.risk.ipintel.local;

import java.math.BigDecimal;

/**
 * 表示本地 IP2Location BIN 能够提供的非敏感地理补充结果。
 */
public record LocalIpGeoResult(
        String countryCode,
        Long asn,
        BigDecimal latitude,
        BigDecimal longitude) {
}
