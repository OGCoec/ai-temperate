package com.example.temperate.web.admin.controller;

import com.example.temperate.service.auth.phonecountry.service.PhoneCountryResolutionService;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.web.admin.api.AdminPhoneCountryResponse;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 为管理员注册和登录页面提供独立的手机号默认国家识别 HTTP 接口。
 *
 * <p>该控制器只定义管理员端协议；H5 使用 Worker 验签后的客户端公网 IP，Android 无 Origin 直连时
 * 使用受控代理解析结果，IP2Location 查询和八秒期限继续复用普通用户的共享组件。</p>
 *
 * <p>安全与隐私边界：控制器不直接读取任何客户端可伪造的 IP 请求头，接口不要求管理员会话，
 * 也不返回客户端 IP 或精确地理位置。</p>
 */
@RestController
@RequestMapping("/api/admin/auth")
@Tag(
        name = "管理员认证-手机号国家识别",
        description = "为管理员 H5 和 Android 注册、登录页面提供基于可信客户端公网 IP 的默认手机号国家建议。"
                + "接口不要求管理员会话，不返回客户端 IP 或地理位置明细。")
public final class AdminPhoneCountryController {

    private final PhoneCountryResolutionService phoneCountryResolutionService;
    private final RiskRequestContextResolver riskRequestContextResolver;

    public AdminPhoneCountryController(
            PhoneCountryResolutionService phoneCountryResolutionService,
            RiskRequestContextResolver riskRequestContextResolver) {
        this.phoneCountryResolutionService = Objects.requireNonNull(
                phoneCountryResolutionService);
        this.riskRequestContextResolver = Objects.requireNonNull(
                riskRequestContextResolver);
    }

    /**
     * 根据统一可信网络上下文中的公网 IP 返回管理员手机号默认国家建议。
     *
     * <p>H5 缺少已验签边缘上下文时不得降级读取转发头；无法取得可信公网 IP 时正常返回
     * {@code resolved=false}。共享查询超过配置期限时保留 {@code PHONE_COUNTRY_TIMEOUT} 错误，
     * 不得降级为普通无结果。</p>
     */
    @GetMapping("/phone-country")
    @Operation(
            summary = "识别管理员手机号默认国家",
            description = "返回大写 ISO2 国家代码；无法识别时返回 HTTP 200 和 resolved=false。"
                    + "H5 只使用 Worker 验签后的公网 IP，Android 无 Origin 直连时才允许使用受控代理解析结果；"
                    + "共享国家查询超过八秒默认期限时返回 429 PHONE_COUNTRY_TIMEOUT。")
    public Mono<ResponseEntity<AdminPhoneCountryResponse>> resolvePhoneCountry(
            HttpServletRequest request) {
        // 与普通用户共用同一可信上下文边界，禁止 Worker 回源地址覆盖已验签的真实客户端公网 IP。
        Optional<TrustedNetworkObservation> observation =
                riskRequestContextResolver.resolve(request);
        if (observation.isEmpty()) {
            return Mono.just(response(Optional.empty()));
        }

        // 共享 Service 保持普通用户与管理员的异步隔离、Fail Open 和超时语义完全一致。
        return phoneCountryResolutionService
                .resolveCountryIso2(observation.orElseThrow().clientIp())
                .map(AdminPhoneCountryController::response);
    }

    private static ResponseEntity<AdminPhoneCountryResponse> response(
            Optional<String> countryIso2) {
        AdminPhoneCountryResponse body = new AdminPhoneCountryResponse(
                countryIso2.isPresent(),
                countryIso2.orElse(null));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(body);
    }
}
