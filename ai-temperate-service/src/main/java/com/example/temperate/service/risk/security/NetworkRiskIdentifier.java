package com.example.temperate.service.risk.security;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.domain.RiskScope;
import java.util.Objects;

/**
 * 为网络风险域中的 IP、令牌、设备和决策上下文生成用途隔离的稳定 HMAC 标识。
 *
 * <p>该组件保证 Redis Key 和状态字段不会保存明文 IP、PreAuth Token 或设备标识；不同用途带有固定前缀，
 * 避免同一个输入在不同安全语义之间被错误关联。</p>
 */
public final class NetworkRiskIdentifier {

    private final HmacSha256Identifier identifier;

    public NetworkRiskIdentifier(HmacSha256Identifier identifier) {
        this.identifier = Objects.requireNonNull(identifier);
    }

    public HmacIdentifier identifyIp(String clientIp) {
        IpAddressIdentity identity = IpAddressIdentity.parse(clientIp);
        return identifier.identify("risk-ip:v2", identity.hmacPayload());
    }

    public HmacIdentifier identifyPreAuthToken(String rawToken) {
        return identifyOpaque("risk-preauth", rawToken);
    }

    public HmacIdentifier identifyDevice(String rawDeviceId) {
        return identifyOpaque("risk-device", rawDeviceId);
    }

    public HmacIdentifier identifySession(String rawSessionReference) {
        return identifyOpaque("risk-session", rawSessionReference);
    }

    public HmacIdentifier identifyChallenge(String rawReference) {
        return identifyOpaque("risk-challenge", rawReference);
    }

    /**
     * 从 PreAuth 摘要、决策上下文和随机 Nonce 派生不可伪造的 Challenge 引用。
     *
     * <p>Redis 只保存 Nonce 和绑定摘要，不保存客户端收到的原始引用；相同活动上下文因此可以稳定复用。</p>
     */
    public String deriveChallengeReference(
            RiskScope scope,
            HmacIdentifier preAuthDigest,
            HmacIdentifier contextDigest,
            String nonce) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(preAuthDigest);
        Objects.requireNonNull(contextDigest);
        return identifyOpaque(
                "risk-challenge-reference",
                scope.name()
                        + "|"
                        + preAuthDigest.value()
                        + "|"
                        + contextDigest.value()
                        + "|"
                        + nonce)
                .value();
    }

    public HmacIdentifier identifyDecisionContext(String canonicalContext) {
        return identifyOpaque("risk-decision-context", canonicalContext);
    }

    public HmacIdentifier identifyTravelEvent(String canonicalEvent) {
        return identifyOpaque("risk-travel-event", canonicalEvent);
    }

    /**
     * 规范化数字形式的 IPv4/IPv6；输入字符先限于地址语法，避免解析器把主机名交给 DNS。
     */
    public String canonicalIp(String value) {
        return IpAddressIdentity.parse(value).canonicalText();
    }

    private HmacIdentifier identifyOpaque(String purpose, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Risk identifier input is required.");
        }
        return identifier.identify(purpose + "\u0000" + value.trim());
    }
}
