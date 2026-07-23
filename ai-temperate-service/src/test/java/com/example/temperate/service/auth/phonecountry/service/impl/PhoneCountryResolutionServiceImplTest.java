package com.example.temperate.service.auth.phonecountry.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.auth.phonecountry.provider.IpCountryProvider;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证国家代码解析服务对非法输入和提供者异常的空值降级行为。
 */
class PhoneCountryResolutionServiceImplTest {

    @Test
    void normalizesAValidCountryCodeToUppercase() {
        IpCountryProvider provider = ignored -> Optional.of(" us ");
        PhoneCountryResolutionServiceImpl service = new PhoneCountryResolutionServiceImpl(provider);

        assertThat(service.resolveCountryIso2("8.8.8.8")).contains("US");
    }

    @Test
    void rejectsMissingOrNonIso2CountryCodes() {
        assertThat(serviceReturning(Optional.empty()).resolveCountryIso2("8.8.8.8")).isEmpty();
        assertThat(serviceReturning(Optional.of("USA")).resolveCountryIso2("8.8.8.8")).isEmpty();
        assertThat(serviceReturning(Optional.of("-")).resolveCountryIso2("8.8.8.8")).isEmpty();
    }

    @Test
    void failsOpenWhenTheProviderThrows() {
        IpCountryProvider provider = ignored -> {
            throw new IllegalStateException("lookup unavailable");
        };
        PhoneCountryResolutionServiceImpl service = new PhoneCountryResolutionServiceImpl(provider);

        assertThat(service.resolveCountryIso2("8.8.8.8")).isEmpty();
    }

    @Test
    void ignoresBlankClientAddressesWithoutCallingTheProvider() {
        IpCountryProvider provider = ignored -> {
            throw new AssertionError("provider should not be called");
        };
        PhoneCountryResolutionServiceImpl service = new PhoneCountryResolutionServiceImpl(provider);

        assertThat(service.resolveCountryIso2("  ")).isEmpty();
    }

    private static PhoneCountryResolutionServiceImpl serviceReturning(Optional<String> result) {
        return new PhoneCountryResolutionServiceImpl(ignored -> result);
    }
}
