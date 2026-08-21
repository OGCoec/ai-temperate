package com.example.temperate.service.user.membership.payment.loadtest;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentLoadtestProperties;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 该启动守卫是来阻止生产或未知 Profile 开启 AT-only 压测认证，并把四十秒级 fast 时间合同限制在专用 Profile。
 */
@Component
public final class MembershipPaymentLoadtestProfileGuard implements InitializingBean {

    private static final Set<String> ALLOWED_PROFILES =
            Set.of("local-dev", "test", "loadtest-fast", "loadtest-realtime");

    private final MembershipPaymentLoadtestProperties loadtestProperties;
    private final MembershipPaymentProperties paymentProperties;
    private final Environment environment;

    public MembershipPaymentLoadtestProfileGuard(
            MembershipPaymentLoadtestProperties loadtestProperties,
            MembershipPaymentProperties paymentProperties,
            Environment environment) {
        this.loadtestProperties = Objects.requireNonNull(loadtestProperties);
        this.paymentProperties = Objects.requireNonNull(paymentProperties);
        this.environment = Objects.requireNonNull(environment);
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /**
     * 在容器完成属性绑定后验证开关、Profile 与时间合同必须作为一个整体生效，避免普通环境通过变量缩短关单周期。
     */
    public void validate() {
        Set<String> activeProfiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if (loadtestProperties.enabled()) {
            if (!paymentProperties.enabled()) {
                throw new IllegalStateException(
                        "Membership payment must be enabled before loadtest authentication.");
            }
            if (activeProfiles.isEmpty()
                    || activeProfiles.stream().anyMatch(profile -> !ALLOWED_PROFILES.contains(profile))) {
                throw new IllegalStateException(
                        "Membership payment loadtest authentication is not allowed for the active Profile set.");
            }
        }

        if (paymentProperties.usesFastTimingContract()
                && (!loadtestProperties.enabled()
                        || !activeProfiles.contains("loadtest-fast"))) {
            throw new IllegalStateException(
                    "The fast membership payment timing contract requires enabled loadtest authentication and the loadtest-fast Profile.");
        }
        if (activeProfiles.contains("loadtest-fast")
                && loadtestProperties.enabled()
                && !paymentProperties.usesFastTimingContract()) {
            throw new IllegalStateException(
                    "The loadtest-fast Profile requires the fixed fast membership payment timing contract.");
        }
        if (activeProfiles.contains("loadtest-realtime")
                && paymentProperties.usesFastTimingContract()) {
            throw new IllegalStateException(
                    "The loadtest-realtime Profile must retain the five-minute membership payment timing contract.");
        }
    }
}
