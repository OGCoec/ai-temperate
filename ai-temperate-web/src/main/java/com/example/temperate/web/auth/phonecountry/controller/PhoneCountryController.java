package com.example.temperate.web.auth.phonecountry.controller;

import com.example.temperate.service.auth.phonecountry.service.PhoneCountryResolutionService;
import com.example.temperate.web.auth.phonecountry.api.PhoneCountryResponse;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证页面电话号码默认国家建议的 HTTP 接口控制器。
 *
 * <p>用途：基于可信解析到的客户端 IP 查询 ISO2 国家建议，并始终以无缓存的最小响应返回。</p>
 *
 * <p>隐私边界：接口不是精确定位服务；无法识别时正常返回 {@code resolved=false}，不回传 IP 或地理明细。</p>
 */
@RestController
@RequestMapping("/api/auth")
@Tag(
        name = "认证-手机号国家识别",
        description = "为 H5 和 Android 认证页面提供基于客户端 IP 的默认手机号国家建议。"
                + "接口不要求登录，不返回客户端 IP、城市、经纬度或可信度，也不负责精确定位。")
public final class PhoneCountryController {

    private final PhoneCountryResolutionService phoneCountryResolutionService;
    private final TrustedClientIpResolver trustedClientIpResolver;

    public PhoneCountryController(
            PhoneCountryResolutionService phoneCountryResolutionService,
            TrustedClientIpResolver trustedClientIpResolver) {
        this.phoneCountryResolutionService = phoneCountryResolutionService;
        this.trustedClientIpResolver = trustedClientIpResolver;
    }

    @GetMapping("/phone-country")
    @Operation(
            summary = "根据客户端 IP 识别手机号默认国家",
            description = "只返回大写 ISO2 国家代码；无法识别时返回 HTTP 200 和 resolved=false，"
                    + "由客户端继续使用设备地区，设备地区不可用时要求用户手动选择。")
    public ResponseEntity<PhoneCountryResponse> resolvePhoneCountry(HttpServletRequest request) {
        // 解析失败或国家库无结果都安全降级为 resolved=false，避免认证页面依赖地理定位可用性。
        Optional<String> countryIso2 = trustedClientIpResolver.resolve(request)
                .flatMap(phoneCountryResolutionService::resolveCountryIso2);
        PhoneCountryResponse response = new PhoneCountryResponse(
                countryIso2.isPresent(),
                countryIso2.orElse(null));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(response);
    }
}
