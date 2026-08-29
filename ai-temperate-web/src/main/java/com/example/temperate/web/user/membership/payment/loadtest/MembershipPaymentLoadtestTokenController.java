package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestToken;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该 Controller 是来为回环 JMeter Runner 获取十六个固定会员账号的十五小时签名 Token，不接收账号创建、用户输入或外部请求。
 *
 * <p>入口只在 loadtest 开关开启时注册，并额外限制 Servlet 回环地址；Token 由服务层调用正式 JWT 签发器生成，
 * Controller 不记录、不持久化也不回显到日志。</p>
 */
@RestController
@RequestMapping(MembershipPaymentLoadtestTokenController.PATH)
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-压测认证",
        description = "仅供回环 loadtest Runner 获取十六个固定白名单账号的十五小时 Access Token；限制回环地址，不创建账号、不修改会员数据，也不替代生产认证接口。")
public final class MembershipPaymentLoadtestTokenController {

    public static final String PATH =
            "/internal/test/membership-payments/loadtest-tokens";

    private final MembershipPaymentLoadtestTokenService tokenService;

    public MembershipPaymentLoadtestTokenController(
            MembershipPaymentLoadtestTokenService tokenService) {
        this.tokenService = Objects.requireNonNull(tokenService);
    }

    @PostMapping
    @Operation(summary = "为本机会员支付边界测试签发短期 Access Token")
    public ResponseEntity<Response> issueTokens(HttpServletRequest request) {
        if (!isLoopback(request)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Loadtest token minting is restricted to loopback requests.");
        }
        List<UserToken> users = tokenService.issueForAllowlistedUsers().stream()
                .map(token -> new UserToken(token.userId(), token.accessToken()))
                .toList();
        MembershipPaymentLoadtestToken nonAllowlisted =
                tokenService.issueNonAllowlistedToken();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new Response(
                        users,
                        tokenService.issueExpiredToken(),
                        new UserToken(
                                nonAllowlisted.userId(),
                                nonAllowlisted.accessToken())));
    }

    private static boolean isLoopback(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String remoteAddress = request.getRemoteAddr();
        return "127.0.0.1".equals(remoteAddress)
                || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }

    /** 该响应是来让 Runner 按用户 ID 将签名 Token 写入本地忽略文件，接口本身不保存 Token。 */
    public record Response(
            List<UserToken> users,
            String expiredAccessToken,
            UserToken nonAllowlistedUser) {

        public Response {
            users = users == null ? List.of() : List.copyOf(users);
            if (expiredAccessToken == null || expiredAccessToken.isBlank()) {
                throw new IllegalArgumentException("Expired loadtest token is required.");
            }
            nonAllowlistedUser = Objects.requireNonNull(nonAllowlistedUser);
        }
    }

    /** 该条目是来绑定既有用户 ID 与本次运行短期 Token，禁止脱离本地测试响应长期使用。 */
    public record UserToken(long userId, String accessToken) {
    }
}
