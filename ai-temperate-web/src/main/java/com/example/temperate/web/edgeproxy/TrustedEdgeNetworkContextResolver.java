package com.example.temperate.web.edgeproxy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 仅从边缘验签 Filter 写入的请求属性解析可信 IP、国家、ASN 和坐标。
 */
@Component
public final class TrustedEdgeNetworkContextResolver {

    public static final String VERIFIED_NETWORK_CONTEXT_ATTRIBUTE =
            TrustedEdgeNetworkContextResolver.class.getName() + ".verifiedNetworkContext";

    public Optional<TrustedEdgeNetworkContext> resolve(HttpServletRequest request) {
        Object value = request.getAttribute(VERIFIED_NETWORK_CONTEXT_ATTRIBUTE);
        return value instanceof TrustedEdgeNetworkContext context
                ? Optional.of(context)
                : Optional.empty();
    }
}
