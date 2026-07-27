package com.example.temperate.service.auth.phonecountry.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.auth.phonecountry.provider.IpCountryProvider;
import com.example.temperate.service.auth.phonecountry.service.exception.PhoneCountryTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证国家代码解析服务的惰性执行、规范化、空值降级和受控超时边界。
 */
class PhoneCountryResolutionServiceImplTest {

    @Test
    void normalizesAValidCountryCodeToUppercase() {
        IpCountryProvider provider = ignored -> Optional.of(" us ");
        PhoneCountryResolutionServiceImpl service = service(provider);

        StepVerifier.create(service.resolveCountryIso2("8.8.8.8"))
                .expectNext(Optional.of("US"))
                .verifyComplete();
    }

    @Test
    void rejectsMissingOrNonIso2CountryCodes() {
        verifyEmpty(serviceReturning(Optional.empty()).resolveCountryIso2("8.8.8.8"));
        verifyEmpty(serviceReturning(Optional.of("USA")).resolveCountryIso2("8.8.8.8"));
        verifyEmpty(serviceReturning(Optional.of("-")).resolveCountryIso2("8.8.8.8"));
    }

    @Test
    void failsOpenWhenTheProviderThrows() {
        IpCountryProvider provider = ignored -> {
            throw new IllegalStateException("lookup unavailable");
        };
        PhoneCountryResolutionServiceImpl service = service(provider);

        verifyEmpty(service.resolveCountryIso2("8.8.8.8"));
    }

    @Test
    void ignoresBlankClientAddressesWithoutCallingTheProvider() {
        IpCountryProvider provider = ignored -> {
            throw new AssertionError("provider should not be called");
        };
        PhoneCountryResolutionServiceImpl service = service(provider);

        verifyEmpty(service.resolveCountryIso2("  "));
    }

    @Test
    void doesNotCallTheProviderBeforeSubscription() {
        AtomicBoolean called = new AtomicBoolean(false);
        PhoneCountryResolutionServiceImpl service = service(ignored -> {
            called.set(true);
            return Optional.of("US");
        });

        Mono<Optional<String>> result = service.resolveCountryIso2("8.8.8.8");

        assertThat(called).isFalse();
        StepVerifier.create(result)
                .expectNext(Optional.of("US"))
                .verifyComplete();
        assertThat(called).isTrue();
    }

    @Test
    void emitsTheDedicatedExceptionWhenTheLookupExceedsItsDeadline() {
        CountDownLatch release = new CountDownLatch(1);
        IpCountryProvider provider = ignored -> {
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        };
        PhoneCountryResolutionServiceImpl service =
                new PhoneCountryResolutionServiceImpl(provider, Duration.ofMillis(25));

        try {
            StepVerifier.create(service.resolveCountryIso2("8.8.8.8"))
                    .expectError(PhoneCountryTimeoutException.class)
                    .verify(Duration.ofSeconds(1));
        } finally {
            release.countDown();
        }
    }

    private static PhoneCountryResolutionServiceImpl serviceReturning(Optional<String> result) {
        return service(ignored -> result);
    }

    private static PhoneCountryResolutionServiceImpl service(IpCountryProvider provider) {
        return new PhoneCountryResolutionServiceImpl(provider, Duration.ofSeconds(8));
    }

    private static void verifyEmpty(Mono<Optional<String>> result) {
        StepVerifier.create(result)
                .expectNext(Optional.empty())
                .verifyComplete();
    }
}
