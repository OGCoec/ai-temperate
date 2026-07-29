package com.example.temperate.web.admin.mailinspection;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.web.admin.mailinspection.api.AdminMailInspectionSseEventResponse;
import com.example.temperate.web.admin.mailinspection.sse.MailInspectionSseService;
import com.example.temperate.web.admin.security.AdminSessionAuthenticationInterceptor;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.example.temperate.web.auth.diagnostic.filter.AuthRequestTraceFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 提供管理员邮件任务的单向 SSE 入口，并复用现有管理员会话、PreAuth、网络风险和 trace 安全边界。
 */
@RestController
@RequestMapping("/api/admin/mail-inspection")
@Tag(
        name = "管理员-邮箱检查实时事件",
        description =
                "仅供已登录管理员订阅 Redis 权威邮件任务快照、进度、结果和终态；"
                        + "接口不接受 Token 查询参数，也不承担任务创建或邮箱扫描。")
public final class AdminMailInspectionSseController {

    private final MailInspectionSseService sseService;

    public AdminMailInspectionSseController(
            MailInspectionSseService sseService) {
        this.sseService = Objects.requireNonNull(sseService);
    }

    @GetMapping(
            value = "/jobs/{jobId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "订阅邮箱检查任务实时事件",
            description =
                    "客户端必须同时接受 text/event-stream 与 application/json；"
                            + "任务存在时返回增量事件流，Redis 权威任务不存在时返回受控 404 JSON。")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "任务存在，返回无缓存的 SSE 增量事件",
                content = @Content(
                        mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                        schema = @Schema(
                                implementation =
                                        AdminMailInspectionSseEventResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "任务已过期、已删除或不存在，返回稳定 JSON 错误码",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(
                                implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<SseEmitter> events(
            @PathVariable
            @Parameter(
                    description = "固定 22 位规范 Base64URL 任务公共 ID。",
                    schema = @Schema(
                            minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            pattern = HybridBase64UrlCodec.FORMAT,
                            example = "AZ9nEjRWeJCrze8SNFZ4kA"))
                    MailInspectionJobPublicId jobId,
            @RequestHeader(
                    name = "Last-Event-ID",
                    required = false)
            @Parameter(
                    description = "客户端最后完整处理的非负 Redis revision；"
                            + "服务端仍会重读 Redis 权威快照。",
                    schema = @Schema(
                            pattern = "^[0-9]+$",
                            example = "17"))
                    String lastEventId,
            HttpServletRequest request) {
        String traceId = Objects.toString(
                request.getAttribute(AuthRequestTraceFilter.TRACE_ATTRIBUTE),
                "absent");
        SseEmitter emitter = sseService.connect(
                jobId.value(),
                lastEventId,
                sessionKey(request));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store, private, no-transform")
                .header("X-Accel-Buffering", "no")
                .header(AuthRequestTraceFilter.TRACE_HEADER, traceId)
                .body(emitter);
    }

    private static String sessionKey(HttpServletRequest request) {
        Object raw = request.getAttribute(
                AdminSessionAuthenticationInterceptor.RAW_TOKEN_ATTRIBUTE);
        if (!(raw instanceof String token) || token.isBlank()) {
            throw new IllegalStateException(
                    "admin session token attribute is unavailable");
        }
        try {
            // 会话 Token 只在当前调用栈内做单向摘要；摘要只用于本实例连接计数且不会写日志或外部存储。
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }
}
