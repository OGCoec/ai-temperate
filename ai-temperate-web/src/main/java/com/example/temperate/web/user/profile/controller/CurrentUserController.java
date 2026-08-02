package com.example.temperate.web.user.profile.controller;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.profile.CurrentUserProfileResult;
import com.example.temperate.service.user.profile.CurrentUserProfileService;
import com.example.temperate.web.user.profile.api.CurrentUserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为 H5 和 Android 个人中心提供当前已认证用户的最小资料接口，并禁止客户端指定其他用户 ID。
 */
@RestController
@RequestMapping("/api/users")
@Tag(
        name = "用户-当前用户资料",
        description = "为已通过 Access Token 认证的 H5 和 Android 客户端返回当前用户资料、会员等级、"
                + "剩余额度和预计重置时间。接口不读取 Refresh Token，不返回内部用户 ID、密码、令牌、"
                + "设备标识或请求 IP，也不负责额度扣减和最终结算。")
public final class CurrentUserController {

    private final CurrentUserProfileService currentUserProfileService;

    public CurrentUserController(CurrentUserProfileService currentUserProfileService) {
        this.currentUserProfileService = currentUserProfileService;
    }

    @GetMapping("/me")
    @Operation(
            summary = "读取当前用户资料",
            description = "用户身份只取自已经验证的 Access Token 安全上下文；响应禁止浏览器和共享代理缓存。")
    public ResponseEntity<CurrentUserResponse> me(
            @AuthenticationPrincipal SessionPrincipal principal) {
        // principal 由统一认证拦截器写入，Controller 不接受客户端提交的用户 ID，也不重复解析 Token。
        CurrentUserProfileResult profile =
                currentUserProfileService.getRequired(principal.userId());
        CurrentUserResponse response = new CurrentUserResponse(
                profile.displayName(),
                profile.email(),
                profile.phone(),
                profile.avatarUrl(),
                profile.membershipTier(),
                profile.quotaBalanceMinor(),
                profile.quotaBalance(),
                profile.quotaTotalMinor(),
                profile.quotaTotal(),
                profile.quotaUsedMinor(),
                profile.quotaUsed(),
                profile.quotaUsagePercent(),
                profile.quotaPeriodStartedAt(),
                profile.quotaResetAt());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(response);
    }
}
