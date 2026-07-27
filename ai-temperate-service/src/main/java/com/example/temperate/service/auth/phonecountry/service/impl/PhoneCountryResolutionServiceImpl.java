package com.example.temperate.service.auth.phonecountry.service.impl;

import com.example.temperate.service.auth.phonecountry.provider.IpCountryProvider;
import com.example.temperate.service.auth.phonecountry.service.PhoneCountryResolutionService;
import com.example.temperate.service.auth.phonecountry.service.exception.PhoneCountryTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 对本地国家代码查询进行异步隔离、期限控制和 Fail Open 规范化的服务实现。
 *
 * <p>国家识别是辅助信息而非认证凭据；普通提供者异常或非规范结果均降级为空，只有超过配置期限才暴露受控超时错误。</p>
 *
 * <p>并发边界：同步 IP2Location 调用被调度到有界弹性线程池，避免占用 Servlet 或 Reactor 事件线程；
 * Reactor 取消只能尽力中断底层调用，不能假设第三方库一定响应线程中断。</p>
 */
@Service
public final class PhoneCountryResolutionServiceImpl implements PhoneCountryResolutionService {

    private final IpCountryProvider ipCountryProvider;
    private final Duration lookupTimeout;

    public PhoneCountryResolutionServiceImpl(
            IpCountryProvider ipCountryProvider,
            @Qualifier("phoneCountryLookupTimeout") Duration lookupTimeout) {
        this.ipCountryProvider = ipCountryProvider;
        if (lookupTimeout == null || lookupTimeout.isZero() || lookupTimeout.isNegative()) {
            throw new IllegalArgumentException("Phone country lookup timeout must be positive.");
        }
        this.lookupTimeout = lookupTimeout;
    }

    @Override
    public Mono<Optional<String>> resolveCountryIso2(String canonicalClientIp) {
        if (canonicalClientIp == null || canonicalClientIp.isBlank()) {
            return Mono.just(Optional.empty());
        }
        String normalizedClientIp = canonicalClientIp.trim();

        // 普通查询异常先降级为空；timeout 放在降级操作之后，确保期限异常不会被误吞为普通无结果。
        return Mono.fromCallable(() -> normalizeCountryIso2(
                        ipCountryProvider.findCountryIso2(normalizedClientIp)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorReturn(Optional.empty())
                .timeout(
                        lookupTimeout,
                        Mono.error(new PhoneCountryTimeoutException()));
    }

    private static Optional<String> normalizeCountryIso2(Optional<String> countryIso2) {
        if (countryIso2 == null) {
            return Optional.empty();
        }
        return countryIso2
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> value.matches("^[A-Z]{2}$"));
    }
}
