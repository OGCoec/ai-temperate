package com.example.temperate.web.edgeproxy;

import java.math.BigDecimal;

/**
 * 表示由 Cloudflare Worker v2 签名并由后端验签成功的客户端网络上下文。
 *
 * <p>该值只能通过请求属性在当前请求内传播，禁止从同名 HTTP Header 直接构造。</p>
 */
public record TrustedEdgeNetworkContext(
        String clientIp,
        String countryCode,
        Long asn,
        BigDecimal latitude,
        BigDecimal longitude,
        String ray) {

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
