package com.example.temperate.web.risk;

import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContext;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 将已验签 Worker v2 网络属性或受信任直连来源转换为请求级风险观测。
 *
 * <p>浏览器请求优先且只使用边缘验签属性；Android 无 Origin 直连时才允许使用现有可信代理 IP
 * 解析器，直连来源不得伪装成 Worker 上下文。</p>
 */
@Component
public final class RiskRequestContextResolver {

    private final TrustedEdgeNetworkContextResolver edgeResolver;
    private final TrustedClientIpResolver directIpResolver;
    private final Clock clock;

    public RiskRequestContextResolver(
            TrustedEdgeNetworkContextResolver edgeResolver,
            TrustedClientIpResolver directIpResolver,
            Clock clock) {
        this.edgeResolver = Objects.requireNonNull(edgeResolver);
        this.directIpResolver = Objects.requireNonNull(directIpResolver);
        this.clock = Objects.requireNonNull(clock);
    }

    public Optional<TrustedNetworkObservation> resolve(HttpServletRequest request) {
        Optional<TrustedEdgeNetworkContext> edge = edgeResolver.resolve(request);
        if (edge.isPresent()) {
            TrustedEdgeNetworkContext value = edge.orElseThrow();
            return Optional.of(new TrustedNetworkObservation(
                    value.clientIp(),
                    value.countryCode(),
                    value.asn(),
                    value.latitude(),
                    value.longitude(),
                    clock.instant()));
        }
        if (hasText(request.getHeader("Origin"))) {
            return Optional.empty();
        }
        return directIpResolver.resolve(request)
                .map(ip -> new TrustedNetworkObservation(
                        ip,
                        null,
                        null,
                        null,
                        null,
                        clock.instant()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
