package com.example.temperate.service.user.membership.payment.callback.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackFingerprintService;
import com.example.temperate.service.user.membership.payment.callback.SimulatedLiuhaoCallbackCommand;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来严格按固定五行原文计算 HMAC-SHA256，并对排序后且移除 sign、buyer 的字段计算 SHA-256 摘要。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.simulator",
        name = "enabled",
        havingValue = "true")
public final class PaymentCallbackFingerprintServiceImpl
        implements PaymentCallbackFingerprintService {

    private static final Base64.Encoder BASE64_URL =
            Base64.getUrlEncoder().withoutPadding();

    private final HmacSha256Identifier hmac;

    public PaymentCallbackFingerprintServiceImpl(
            MembershipPaymentProperties properties) {
        MembershipPaymentProperties.Simulator simulator =
                Objects.requireNonNull(properties).simulator();
        this.hmac = new HmacSha256Identifier(
                simulator.callbackKey().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public HmacIdentifier fingerprint(SimulatedLiuhaoCallbackCommand command) {
        SimulatedLiuhaoCallbackCommand value = Objects.requireNonNull(command);
        String canonical = String.join(
                "\n",
                "SIMULATED",
                value.pid(),
                value.tradeNo(),
                value.outTradeNo(),
                value.tradeStatus());
        return hmac.identify(canonical);
    }

    /**
     * 第三方流水指纹故意不包含我方订单号，使同一流水跨订单复用时仍命中同一个受保护 Redis Key。
     */
    @Override
    public HmacIdentifier providerTradeFingerprint(
            SimulatedLiuhaoCallbackCommand command) {
        SimulatedLiuhaoCallbackCommand value = Objects.requireNonNull(command);
        return hmac.identify(String.join(
                "\n",
                "SIMULATED_PROVIDER_TRADE",
                value.pid(),
                value.tradeNo()));
    }

    @Override
    public String payloadDigest(SimulatedLiuhaoCallbackCommand command) {
        TreeMap<String, String> fields = new TreeMap<>(
                Objects.requireNonNull(command).externalFields());
        fields.remove("sign");
        fields.remove("buyer");
        String canonical = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return BASE64_URL.encodeToString(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
