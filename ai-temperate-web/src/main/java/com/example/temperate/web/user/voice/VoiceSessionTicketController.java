package com.example.temperate.web.user.voice;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.security.VoiceTicketIssueCommand;
import com.example.temperate.service.user.voice.security.VoiceWebSocketAuthorizationService;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketIssue;
import com.example.temperate.web.auth.interceptor.UserSessionAuthenticationInterceptor;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为已完成普通用户会话认证的 H5 和 Android 客户端签发一次性语音 WebSocket 票据。
 *
 * <p>该 Controller 只负责 HTTP 传输和主体提取；票据随机性、限流、HMAC 与 Redis 单次消费由 Service 负责。</p>
 */
@RestController
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
@RequestMapping("/api/users/me/voice/session-tickets")
@Tag(
        name = "用户-语音输入",
        description = "供已认证 H5 和 Android 用户申请短期单次语音 WebSocket 票据；接口不接收音频、不执行转写，也不会自动发送 AI 对话消息。")
public final class VoiceSessionTicketController {

    private static final int PROTOCOL_VERSION = 2;
    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String REFRESH_HEADER = "X-Refresh-Token";

    private final VoiceWebSocketAuthorizationService authorizationService;
    private final VoiceProperties properties;

    public VoiceSessionTicketController(
            VoiceWebSocketAuthorizationService authorizationService,
            VoiceProperties properties) {
        this.authorizationService = authorizationService;
        this.properties = properties;
    }

    @PostMapping
    @Operation(
            summary = "申请一次性语音连接票据",
            description = "票据三十秒内有效且只能消费一次；响应禁止缓存，票据必须通过 Sec-WebSocket-Protocol 提交，不得放入 URL、Cookie、Authorization 或消息帧。")
    public ResponseEntity<VoiceSessionTicketResponse> issue(
            @RequestHeader(DEVICE_HEADER) String deviceInstallationId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request) {
        SessionPrincipal principal = principal(request);
        VoiceClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader)
                == AuthClientPlatform.ANDROID
                ? VoiceClientPlatform.ANDROID
                : VoiceClientPlatform.H5;
        String rawRefreshToken = platform == VoiceClientPlatform.ANDROID
                ? request.getHeader(REFRESH_HEADER)
                : cookie(request, AuthCookieWriter.REFRESH_COOKIE);
        VoiceSessionTicketIssue issue = authorizationService.issueTicket(
                new VoiceTicketIssueCommand(
                        principal,
                        platform,
                        deviceInstallationId,
                        rawRefreshToken,
                        preAuthAccess(request)));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new VoiceSessionTicketResponse(
                        issue.ticket(),
                        PROTOCOL_VERSION,
                        issue.expiresAt(),
                        properties.maxDuration().toMillis(),
                        properties.partialInterval().toMillis()));
    }

    private static SessionPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(
                UserSessionAuthenticationInterceptor.PRINCIPAL_ATTRIBUTE);
        if (value instanceof SessionPrincipal principal) {
            return principal;
        }
        throw new VoiceException(
                VoiceErrorCode.VOICE_TICKET_INVALID,
                "当前登录会话无法签发语音连接票据。",
                false);
    }

    private static PreAuthAccess preAuthAccess(HttpServletRequest request) {
        Object value = request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        if (value instanceof PreAuthAccess access) {
            return access;
        }
        throw new VoiceException(
                VoiceErrorCode.VOICE_PREAUTH_REQUIRED,
                "Voice ticket requires an authenticated PreAuth binding.",
                false);
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 返回语音协议固定边界和短期票据；toString 永远不输出票据原文。
     */
    public record VoiceSessionTicketResponse(
            String ticket,
            int protocolVersion,
            Instant expiresAt,
            long maxDurationMs,
            long partialIntervalMs) {

        @Override
        public String toString() {
            return "VoiceSessionTicketResponse[ticket=redacted, protocolVersion="
                    + protocolVersion + ", expiresAt=" + expiresAt
                    + ", maxDurationMs=" + maxDurationMs
                    + ", partialIntervalMs=" + partialIntervalMs + "]";
        }
    }
}
