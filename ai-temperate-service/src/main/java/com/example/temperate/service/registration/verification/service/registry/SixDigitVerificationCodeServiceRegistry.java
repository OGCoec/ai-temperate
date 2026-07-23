package com.example.temperate.service.registration.verification.service.registry;

import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 注册并选择全部六位数验证码供应商服务。
 *
 * <p>构造阶段从 Spring 注入的 Bean Map 中读取每个实现声明的稳定枚举类型，检测重复后冻结为不可变
 * EnumMap；运行期不依赖 Bean 名、Map 顺序或客户端输入。</p>
 */
@Component
public final class SixDigitVerificationCodeServiceRegistry {

    private final Map<VerificationProvider, SixDigitVerificationCodeService> services;

    public SixDigitVerificationCodeServiceRegistry(
            Map<String, SixDigitVerificationCodeService> serviceBeans) {
        Objects.requireNonNull(serviceBeans, "serviceBeans must not be null");
        EnumMap<VerificationProvider, SixDigitVerificationCodeService> registered =
                new EnumMap<>(VerificationProvider.class);
        for (SixDigitVerificationCodeService service : serviceBeans.values()) {
            Objects.requireNonNull(service, "service bean must not be null");
            VerificationProvider provider =
                    Objects.requireNonNull(service.type(), "service type must not be null");
            SixDigitVerificationCodeService previous = registered.put(provider, service);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate six-digit verification provider: " + provider);
            }
        }
        this.services = Collections.unmodifiableMap(registered);
    }

    public SixDigitVerificationCodeService getRequired(VerificationProvider provider) {
        SixDigitVerificationCodeService service = services.get(provider);
        if (service == null) {
            throw new RegistrationException(
                    RegistrationErrorCode.VERIFICATION_CHANNEL_UNSUPPORTED,
                    "Unsupported verification provider.");
        }
        return service;
    }

    public Set<VerificationProvider> registeredTypes() {
        return Set.copyOf(services.keySet());
    }
}
