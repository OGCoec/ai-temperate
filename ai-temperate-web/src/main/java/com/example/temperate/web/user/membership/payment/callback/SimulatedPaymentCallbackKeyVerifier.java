package com.example.temperate.web.user.membership.payment.callback;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该验证器是来使用常量时间字节比较校验模拟支付测试密钥，避免普通字符串提前退出暴露匹配前缀信息。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment.simulator",
        name = "enabled",
        havingValue = "true")
public final class SimulatedPaymentCallbackKeyVerifier {

    private final byte[] expected;

    public SimulatedPaymentCallbackKeyVerifier(MembershipPaymentProperties properties) {
        this.expected = Objects.requireNonNull(properties)
                .simulator()
                .callbackKey()
                .getBytes(StandardCharsets.UTF_8);
    }

    public boolean matches(String candidate) {
        byte[] actual = candidate == null
                ? new byte[0]
                : candidate.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
