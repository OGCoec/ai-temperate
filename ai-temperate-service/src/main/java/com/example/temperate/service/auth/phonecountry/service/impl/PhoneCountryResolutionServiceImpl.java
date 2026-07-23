package com.example.temperate.service.auth.phonecountry.service.impl;

import com.example.temperate.service.auth.phonecountry.provider.IpCountryProvider;
import com.example.temperate.service.auth.phonecountry.service.PhoneCountryResolutionService;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 对国家代码提供者结果做规范化并以 Fail Open 方式暴露的服务实现。
 *
 * <p>国家识别是辅助信息而非认证凭据；任意提供者异常或非规范结果均降级为空，不能阻断登录或伪造国家值。</p>
 */
@Service
public final class PhoneCountryResolutionServiceImpl implements PhoneCountryResolutionService {

    private final IpCountryProvider ipCountryProvider;

    public PhoneCountryResolutionServiceImpl(IpCountryProvider ipCountryProvider) {
        this.ipCountryProvider = ipCountryProvider;
    }

    @Override
    public Optional<String> resolveCountryIso2(String canonicalClientIp) {
        if (canonicalClientIp == null || canonicalClientIp.isBlank()) {
            return Optional.empty();
        }
        try {
            return ipCountryProvider.findCountryIso2(canonicalClientIp.trim())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .filter(value -> value.matches("^[A-Z]{2}$"));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
