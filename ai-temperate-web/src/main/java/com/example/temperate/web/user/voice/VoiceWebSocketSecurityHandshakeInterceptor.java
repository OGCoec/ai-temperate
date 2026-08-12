package com.example.temperate.web.user.voice;

import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.diagnostic.VoiceDiagnosticContext;
import com.example.temperate.service.user.voice.security.VoiceHandshakeCommand;
import com.example.temperate.service.user.voice.security.VoiceHandshakePrincipal;
import com.example.temperate.service.user.voice.security.VoiceWebSocketAuthorizationService;
import com.example.temperate.web.edgeproxy.TrustedEdgeNetworkContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 在 Voice WebSocket 返回 101 前消费 v2 Ticket，并完成全部可撤销安全状态复核。
 *
 * <p>Origin 拦截器必须先执行，避免非法来源消耗合法一次性 Ticket；成功属性只保留最小主体。</p>
 */
@Component
public final class VoiceWebSocketSecurityHandshakeInterceptor
        implements HandshakeInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            VoiceWebSocketSecurityHandshakeInterceptor.class);
    private static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

    private final VoiceWebSocketAuthorizationService authorizationService;
    private final TrustedEdgeNetworkContextResolver networkContextResolver;

    public VoiceWebSocketSecurityHandshakeInterceptor(
            VoiceWebSocketAuthorizationService authorizationService,
            TrustedEdgeNetworkContextResolver networkContextResolver) {
        this.authorizationService = Objects.requireNonNull(authorizationService);
        this.networkContextResolver = Objects.requireNonNull(networkContextResolver);
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        VoiceDiagnosticContext diagnosticContext = diagnosticContext(request);
        Object platformDiagnostic = attributes.get(
                VoiceWebSocketOriginInterceptor.PLATFORM_ATTRIBUTE);
        Object originDiagnostic = attributes.get(
                VoiceWebSocketOriginInterceptor.ORIGIN_PRESENT_ATTRIBUTE);
        final VoiceWebSocketProtocolParser.ParsedVoiceProtocol protocol;
        try {
            List<String> protocolHeaders = request.getHeaders().get(
                    SEC_WEBSOCKET_PROTOCOL);
            if (protocolHeaders == null || protocolHeaders.size() != 1) {
                return rejectAndLog(
                        response,
                        HttpStatus.BAD_REQUEST,
                        diagnosticContext,
                        false,
                        false,
                        platformDiagnostic,
                        originDiagnostic,
                        "VOICE_PROTOCOL_INVALID",
                        "ABSENT");
            }
            protocol = VoiceWebSocketProtocolParser.parse(
                    protocolHeaders.getFirst());
        } catch (IllegalArgumentException exception) {
            return rejectAndLog(
                    response,
                    HttpStatus.BAD_REQUEST,
                    diagnosticContext,
                    false,
                    false,
                    platformDiagnostic,
                    originDiagnostic,
                    "VOICE_PROTOCOL_INVALID",
                    safeExceptionType(exception));
        }

        Object platformValue = attributes.remove(
                VoiceWebSocketOriginInterceptor.PLATFORM_ATTRIBUTE);
        Object originValue = attributes.remove(
                VoiceWebSocketOriginInterceptor.ORIGIN_PRESENT_ATTRIBUTE);
        if (!(platformValue instanceof VoiceClientPlatform platform)
                || !(originValue instanceof Boolean originPresent)
                || !(request instanceof ServletServerHttpRequest servletRequest)) {
            return rejectAndLog(
                    response,
                    HttpStatus.FORBIDDEN,
                    diagnosticContext,
                    true,
                    false,
                    platformValue,
                    originValue,
                    "VOICE_ORIGIN_CONTEXT_INVALID",
                    "ABSENT");
        }

        HttpServletRequest rawRequest = servletRequest.getServletRequest();
        var networkContext = networkContextResolver.resolve(rawRequest);
        String currentHttpIp = networkContext
                .map(context -> context.clientIp())
                .orElse(null);
        if (currentHttpIp == null) {
            return rejectAndLog(
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    diagnosticContext,
                    true,
                    false,
                    platform,
                    originPresent,
                    "VOICE_EDGE_NETWORK_CONTEXT_MISSING",
                    "ABSENT");
        }

        try {
            VoiceHandshakePrincipal principal = authorizationService.authorize(
                    new VoiceHandshakeCommand(
                            protocol.rawTicket(),
                            platform,
                            originPresent,
                            currentHttpIp));
            attributes.clear();
            attributes.put(VoiceHandshakePrincipal.ATTRIBUTE, principal);
            if (diagnosticContext != null) {
                attributes.put(VoiceDiagnosticContext.ATTRIBUTE, diagnosticContext);
            }
            logAuthorization(
                    diagnosticContext,
                    true,
                    true,
                    platform,
                    originPresent,
                    true,
                    "ABSENT",
                    -1,
                    "ABSENT");
            return true;
        } catch (VoiceException exception) {
            return rejectAndLog(
                    response,
                    status(exception.code()),
                    diagnosticContext,
                    true,
                    true,
                    platform,
                    originPresent,
                    exception.code().name(),
                    safeExceptionType(exception));
        } catch (RuntimeException exception) {
            return rejectAndLog(
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    diagnosticContext,
                    true,
                    true,
                    platform,
                    originPresent,
                    VoiceErrorCode.VOICE_INFRASTRUCTURE_UNAVAILABLE.name(),
                    safeExceptionType(exception));
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // Ticket 在 beforeHandshake 中已经完成单次消费，握手结束后不保留任何请求凭据。
    }

    private static HttpStatus status(VoiceErrorCode code) {
        return switch (code) {
            case VOICE_TICKET_INVALID, VOICE_SESSION_INVALID -> HttpStatus.UNAUTHORIZED;
            case VOICE_DEVICE_BLOCKED -> HttpStatus.FORBIDDEN;
            case VOICE_PREAUTH_REQUIRED, VOICE_WEBRTC_REQUIRED ->
                    HttpStatus.PRECONDITION_REQUIRED;
            case VOICE_INFRASTRUCTURE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static boolean reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        response.getHeaders().setCacheControl(CacheControl.noStore());
        return false;
    }

    private static boolean rejectAndLog(
            ServerHttpResponse response,
            HttpStatus status,
            VoiceDiagnosticContext diagnosticContext,
            boolean protocolShapeValid,
            boolean networkContextPresent,
            Object platform,
            Object originPresent,
            String errorCode,
            String exceptionType) {
        reject(response, status);
        logAuthorization(
                diagnosticContext,
                protocolShapeValid,
                networkContextPresent,
                platform,
                originPresent,
                false,
                errorCode,
                status.value(),
                exceptionType);
        return false;
    }

    private static void logAuthorization(
            VoiceDiagnosticContext context,
            boolean protocolShapeValid,
            boolean networkContextPresent,
            Object platform,
            Object originPresent,
            boolean authorized,
            String errorCode,
            int status,
            String exceptionType) {
        String template = "event=voice_ws_authorization traceId={} edgeRay={} "
                + "protocolShapeValid={} networkContextPresent={} platform={} "
                + "originPresent={} authorized={} errorCode={} status={} exceptionType={}";
        Object[] arguments = {
            context == null ? "ABSENT" : context.traceId(),
            context == null ? "ABSENT" : context.edgeRay(),
            protocolShapeValid,
            networkContextPresent,
            safePlatform(platform),
            safeOriginPresent(originPresent),
            authorized,
            errorCode,
            status,
            exceptionType
        };
        try {
            if (authorized) {
                LOGGER.info(template, arguments);
            } else {
                LOGGER.warn(template, arguments);
            }
        } catch (RuntimeException ignored) {
            // 日志后端异常不能改变 Ticket 授权状态或 HTTP 响应码。
        }
    }

    private static VoiceDiagnosticContext diagnosticContext(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return null;
        }
        Object value = servletRequest.getServletRequest().getAttribute(
                VoiceDiagnosticContext.ATTRIBUTE);
        return value instanceof VoiceDiagnosticContext context ? context : null;
    }

    private static String safePlatform(Object value) {
        if (value instanceof VoiceClientPlatform platform) {
            return platform.name();
        }
        return value == null ? "ABSENT" : "INVALID";
    }

    private static String safeOriginPresent(Object value) {
        return value instanceof Boolean present
                ? Boolean.toString(present)
                : value == null ? "ABSENT" : "INVALID";
    }

    private static String safeExceptionType(Throwable exception) {
        String type = exception.getClass().getSimpleName();
        return type.matches("^[A-Za-z0-9_$]{1,128}$") ? type : "INVALID";
    }
}
