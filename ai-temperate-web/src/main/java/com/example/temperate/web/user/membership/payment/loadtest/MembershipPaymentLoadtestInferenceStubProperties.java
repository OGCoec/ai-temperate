package com.example.temperate.web.user.membership.payment.loadtest;

import jakarta.validation.constraints.AssertTrue;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 该配置是来约束 W16 第二回环实例的推理协议替身开关与视频沙箱素材地址，普通共享实例默认关闭。
 */
@Validated
@ConfigurationProperties(
        prefix = "app.membership-payment.loadtest.inference-stub")
public record MembershipPaymentLoadtestInferenceStubProperties(
        boolean enabled,
        String videoUrl) {

    @AssertTrue(message = "Enabled loadtest inference stub requires an HTTPS video URL")
    public boolean isVideoUrlValidWhenEnabled() {
        if (!enabled) {
            return true;
        }
        try {
            URI uri = URI.create(videoUrl == null ? "" : videoUrl.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

