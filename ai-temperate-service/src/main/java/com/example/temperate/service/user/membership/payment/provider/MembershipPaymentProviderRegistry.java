package com.example.temperate.service.user.membership.payment.provider;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 该注册表是来在启动时收集全部支付提供方，并按稳定枚举执行不可变、可验证的策略选择。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPaymentProviderRegistry {

    private final Map<PaymentProviderType, MembershipPaymentProvider> providers;

    @Autowired
    public MembershipPaymentProviderRegistry(
            Map<String, MembershipPaymentProvider> providerBeans,
            MembershipPaymentProperties properties) {
        this(providerBeans);
        PaymentProviderType defaultProvider = Objects.requireNonNull(properties).defaultProvider();
        if (!providers.containsKey(defaultProvider)) {
            throw new IllegalStateException(
                    "Default membership payment provider is not registered: " + defaultProvider);
        }
    }

    /** 该构造器是来支持不启动 Spring 上下文的注册表单元测试，生产装配必须使用包含配置校验的构造器。 */
    MembershipPaymentProviderRegistry(
            Map<String, MembershipPaymentProvider> providerBeans) {
        EnumMap<PaymentProviderType, MembershipPaymentProvider> registered =
                new EnumMap<>(PaymentProviderType.class);
        for (MembershipPaymentProvider provider : providerBeans.values()) {
            MembershipPaymentProvider value = Objects.requireNonNull(provider);
            MembershipPaymentProvider previous = registered.put(value.type(), value);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate membership payment provider: " + value.type());
            }
        }
        this.providers = Map.copyOf(registered);
    }

    /** 不支持的类型必须产生受控失败，禁止把 null 传入后续支付编排。 */
    public MembershipPaymentProvider getRequired(PaymentProviderType type) {
        PaymentProviderType required = Objects.requireNonNull(type, "provider type must not be null");
        MembershipPaymentProvider provider = providers.get(required);
        if (provider == null) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.PAYMENT_PROVIDER_UNSUPPORTED,
                    "Unsupported membership payment provider: " + required);
        }
        return provider;
    }
}
