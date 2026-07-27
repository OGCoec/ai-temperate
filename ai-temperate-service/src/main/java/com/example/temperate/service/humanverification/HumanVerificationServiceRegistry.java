package com.example.temperate.service.humanverification;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 在应用启动时收集全部人机验证 Service，并按稳定枚举建立不可变策略注册表。
 *
 * <p>Spring Bean 名称仅用于框架收集；重复类型或缺少任一必需实现都会立即阻止应用启动，避免请求期静默降级。
 */
@Component
public final class HumanVerificationServiceRegistry {

    private final Map<HumanVerificationType, HumanVerificationService> services;

    /**
     * 收集 Spring 提供的全部实现并一次性校验类型唯一性与完整性。
     *
     * @param serviceBeans Bean 名称到实现的框架收集结果，名称不参与业务选择
     */
    public HumanVerificationServiceRegistry(
            Map<String, HumanVerificationService> serviceBeans) {
        EnumMap<HumanVerificationType, HumanVerificationService> registered =
                new EnumMap<>(HumanVerificationType.class);
        for (HumanVerificationService service :
                Objects.requireNonNull(
                                serviceBeans,
                                "Human verification service beans must not be null.")
                        .values()) {
            HumanVerificationType type = Objects.requireNonNull(
                    service.type(),
                    "Human verification service type must not be null.");
            HumanVerificationService previous = registered.put(type, service);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate human verification service: " + type);
            }
        }
        for (HumanVerificationType requiredType : HumanVerificationType.values()) {
            if (!registered.containsKey(requiredType)) {
                throw new IllegalStateException(
                        "Missing human verification service: " + requiredType);
            }
        }
        this.services = Collections.unmodifiableMap(new EnumMap<>(registered));
    }

    /**
     * 按服务端确定的枚举取得必需策略，不接受客户端 Bean 名称，也不会返回 {@code null}。
     *
     * @param type 服务端选择的人机验证类型
     * @return 已在启动阶段完成唯一性校验的实现
     */
    public HumanVerificationService getRequired(HumanVerificationType type) {
        if (type == null) {
            throw new IllegalArgumentException("Human verification type is required.");
        }
        HumanVerificationType requiredType = type;
        HumanVerificationService service = services.get(requiredType);
        if (service == null) {
            throw new IllegalStateException(
                    "Human verification service is unavailable: " + requiredType);
        }
        return service;
    }
}
