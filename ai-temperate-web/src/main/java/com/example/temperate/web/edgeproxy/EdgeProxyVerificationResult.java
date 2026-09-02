package com.example.temperate.web.edgeproxy;

import java.util.Optional;

/**
 * 汇总一次边缘 HMAC 验签得到的协议版本、外部 Host、Worker Ray 与可选可信网络上下文。
 */
public record EdgeProxyVerificationResult(
        String protocolVersion,
        String externalHost,
        String ray,
        TrustedEdgeNetworkContext networkContext) {

    public Optional<TrustedEdgeNetworkContext> optionalNetworkContext() {
        return Optional.ofNullable(networkContext);
    }
}
