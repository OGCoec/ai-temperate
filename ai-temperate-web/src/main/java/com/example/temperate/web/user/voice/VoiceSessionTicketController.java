package com.example.temperate.web.user.voice;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.config.VoiceProperties;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketIssue;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketService;
import com.example.temperate.web.auth.interceptor.UserSessionAuthenticationInterceptor;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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

    private static final int PROTOCOL_VERSION = 1;
    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";

    private final VoiceSessionTicketService ticketService;
    private final VoiceProperties properties;

    public VoiceSessionTicketController(
            VoiceSessionTicketService ticketService,
            VoiceProperties properties) {
        this.ticketService = ticketService;
        this.properties = properties;
    }

    @PostMapping
    @Operation(
            summary = "申请一次性语音连接票据",
            description = "票据三十秒内有效且只能消费一次；响应禁止缓存，票据必须通过 WSS 首个 JSON 控制帧提交，不得放入 URL。")
    public ResponseEntity<VoiceSessionTicketResponse> issue(
            @RequestHeader(DEVICE_HEADER) String deviceInstallationId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request) {
        SessionPrincipal principal = principal(request);
        VoiceClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader)
                == AuthClientPlatform.ANDROID
                ? VoiceClientPlatform.ANDROID
                : VoiceClientPlatform.H5;
        VoiceSessionTicketIssue issue = ticketService.issue(
                principal.userId(),
                platform,
                deviceInstallationId);
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
