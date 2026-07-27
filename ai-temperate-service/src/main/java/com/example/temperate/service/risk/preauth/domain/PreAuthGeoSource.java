package com.example.temperate.service.risk.preauth.domain;

/**
 * 标识当前 PreAuth 地理字段的主要可信来源，用于解释边缘信息与供应商降级之间的边界。
 */
public enum PreAuthGeoSource {
    CLOUDFLARE_EDGE,
    IP2LOCATION,
    IPING,
    LOCAL_BIN,
    NONE
}
