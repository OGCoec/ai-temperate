package com.example.temperate.web.auth.phonecountry.controller;

import com.example.temperate.service.auth.phonecountry.service.PhoneCountryResolutionService;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.web.auth.phonecountry.api.PhoneCountryResponse;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 认证页面电话号码默认国家建议的 HTTP 接口控制器。
 *
 * <p>用途：H5 使用 Worker 验签后的客户端公网 IP，Android 无 Origin 直连时使用受控代理解析结果，
 * 再查询 ISO2 国家建议并始终以无缓存的最小响应返回。</p>
 *
 * <p>安全与隐私边界：控制器只消费统一可信网络上下文，不直接读取任何客户端可伪造的 IP 请求头；
 * 无法识别时正常返回 {@code resolved=false}，不回传 IP 或地理明细。</p>
 */
@RestController
@RequestMapping("/api/auth")
@Tag(
        name = "认证-手机号国家识别",
        description = "为 H5 和 Android 认证页面提供基于可信客户端公网 IP 的默认手机号国家建议。"
                + "接口不要求登录，不返回客户端 IP、城市、经纬度或可信度，也不负责精确定位。")
public final class PhoneCountryController {

    private final PhoneCountryResolutionService phoneCountryResolutionService;
    private final RiskRequestContextResolver riskRequestContextResolver;

    public PhoneCountryController(
            PhoneCountryResolutionService phoneCountryResolutionService,
            RiskRequestContextResolver riskRequestContextResolver) {
        this.phoneCountryResolutionService = phoneCountryResolutionService;
        this.riskRequestContextResolver = riskRequestContextResolver;
    }

    @GetMapping("/phone-country")
    @Operation(
            summary = "根据客户端 IP 识别手机号默认国家",
            description = "只返回大写 ISO2 国家代码；无法识别时返回 HTTP 200 和 resolved=false，"
                    + "由客户端继续使用设备地区；服务端查询超过配置期限时返回 429 和 "
                    + "PHONE_COUNTRY_TIMEOUT。H5 只使用 Worker 验签后的公网 IP，Android 无 Origin "
                    + "直连时才允许使用受控代理解析结果。")
    public Mono<ResponseEntity<PhoneCountryResponse>> resolvePhoneCountry(
            HttpServletRequest request) {
        // 统一解析器只返回已验签边缘上下文或允许的 Android 直连结果，避免把 Worker 回源地址误当成客户端 IP。
        Optional<TrustedNetworkObservation> observation =
                riskRequestContextResolver.resolve(request);
        if (observation.isEmpty()) {
            return Mono.just(response(Optional.empty()));
        }

        // 查询链保持惰性并由 Spring MVC 异步适配；超时异常继续向全局处理器传播，不能降级成普通无结果。
        return phoneCountryResolutionService.resolveCountryIso2(
                        observation.orElseThrow().clientIp())
                .map(PhoneCountryController::response);
    }

    private static ResponseEntity<PhoneCountryResponse> response(
            Optional<String> countryIso2) {
        PhoneCountryResponse response = new PhoneCountryResponse(
                countryIso2.isPresent(),
                countryIso2.orElse(null));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(response);
    }
}
