package com.example.temperate.web.admin.api;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.web.admin.controller.AdminAiModelIconController;
import java.time.Clock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 是管理员模型图标接口的专用异常边界，把业务错误映射为稳定 HTTP 状态并返回原始异常链诊断信息。
 *
 * <p>诊断字段只作用于经过管理员认证的模型图标 Controller，不修改其他接口使用的全局错误响应。
 */
@RestControllerAdvice(assignableTypes = AdminAiModelIconController.class)
public final class AdminAiModelIconExceptionHandler {

    private final Clock clock;
    private final AdminExceptionLogger exceptionLogger;

    public AdminAiModelIconExceptionHandler(
            Clock clock,
            AdminExceptionLogger exceptionLogger) {
        this.clock = Objects.requireNonNull(clock);
        this.exceptionLogger = Objects.requireNonNull(exceptionLogger);
    }

    @ExceptionHandler(AiModelIconException.class)
    public ResponseEntity<AdminAiModelIconErrorResponse> handle(
            AiModelIconException exception) {
        DiagnosticChain diagnostics = DiagnosticChain.from(exception);
        HttpStatus httpStatus = status(exception.code());
        exceptionLogger.logKnown(
                "admin_ai_model_icon_rejected",
                exception.code().name(),
                httpStatus,
                exception);
        return response(exception.code(), httpStatus, diagnostics);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<AdminAiModelIconErrorResponse> handleTooLarge(
            MaxUploadSizeExceededException exception) {
        DiagnosticChain diagnostics = DiagnosticChain.from(exception);
        AiModelIconErrorCode code = AiModelIconErrorCode.AI_MODEL_ICON_FILE_TOO_LARGE;
        HttpStatus httpStatus = status(code);
        exceptionLogger.logKnown(
                "admin_ai_model_icon_rejected",
                code.name(),
                httpStatus,
                exception);
        return response(code, httpStatus, diagnostics);
    }

    private ResponseEntity<AdminAiModelIconErrorResponse> response(
            AiModelIconErrorCode code,
            HttpStatus httpStatus,
            DiagnosticChain diagnostics) {
        return ResponseEntity.status(httpStatus)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new AdminAiModelIconErrorResponse(
                        code.name(),
                        message(code),
                        diagnostics.exceptionType(),
                        diagnostics.exceptionMessage(),
                        diagnostics.rootCauseType(),
                        diagnostics.rootCauseMessage(),
                        clock.instant()));
    }

    private static HttpStatus status(AiModelIconErrorCode code) {
        return switch (code) {
            case AI_MODEL_ICON_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AI_MODEL_ICON_NAME_CONFLICT,
                    AI_MODEL_ICON_OBJECT_CONFLICT,
                    AI_MODEL_ICON_IN_USE -> HttpStatus.CONFLICT;
            case AI_MODEL_ICON_FILE_TOO_LARGE,
                    AI_MODEL_ICON_REMOTE_RESPONSE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case AI_MODEL_ICON_STORAGE_UNAVAILABLE,
                    AI_MODEL_ICON_DECODER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_MODEL_ICON_REMOTE_DNS_RESOLUTION_FAILED,
                    AI_MODEL_ICON_REMOTE_CONNECT_FAILED,
                    AI_MODEL_ICON_REMOTE_TLS_HANDSHAKE_FAILED,
                    AI_MODEL_ICON_REMOTE_HTTP_STATUS_INVALID -> HttpStatus.BAD_GATEWAY;
            case AI_MODEL_ICON_REMOTE_CONNECT_TIMEOUT,
                    AI_MODEL_ICON_REMOTE_READ_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String message(AiModelIconErrorCode code) {
        return switch (code) {
            case AI_MODEL_ICON_NOT_FOUND -> "模型图标不存在。";
            case AI_MODEL_ICON_NAME_CONFLICT -> "模型图标名称已经存在。";
            case AI_MODEL_ICON_OBJECT_CONFLICT -> "模型图标对象路径已经被占用。";
            case AI_MODEL_ICON_IN_USE -> "模型图标仍被 AI 模型引用，不能删除。";
            case AI_MODEL_ICON_IMAGE_INVALID -> "模型图标文件损坏、格式声明不一致或无法完整解码。";
            case AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED ->
                    "只支持 PNG、JPEG/JPG、WebP、GIF、ICO、AVIF 和 SVG 图标。";
            case AI_MODEL_ICON_IMAGE_UNSAFE ->
                    "模型图标包含危险内容，或动画、容器、尺寸超出安全限制。";
            case AI_MODEL_ICON_DECODER_UNAVAILABLE -> "模型图标解码器暂时不可用。";
            case AI_MODEL_ICON_FILE_TOO_LARGE -> "模型图标文件不能超过 2 MiB。";
            case AI_MODEL_ICON_REMOTE_URL_INVALID -> "外部图标 URL 格式或协议无效。";
            case AI_MODEL_ICON_REMOTE_DNS_RESOLUTION_FAILED -> "外部图标域名解析失败。";
            case AI_MODEL_ICON_REMOTE_HOST_NOT_PUBLIC -> "外部图标域名未解析到允许访问的公网地址。";
            case AI_MODEL_ICON_REMOTE_CONNECT_FAILED -> "无法连接外部图标服务器。";
            case AI_MODEL_ICON_REMOTE_CONNECT_TIMEOUT -> "连接外部图标服务器超时。";
            case AI_MODEL_ICON_REMOTE_READ_TIMEOUT -> "读取外部图标响应超时。";
            case AI_MODEL_ICON_REMOTE_TLS_HANDSHAKE_FAILED -> "外部图标 TLS 握手失败。";
            case AI_MODEL_ICON_REMOTE_REDIRECT_INVALID -> "外部图标重定向地址或跳转次数无效。";
            case AI_MODEL_ICON_REMOTE_HTTP_STATUS_INVALID -> "外部图标服务器返回了不可接受的 HTTP 状态。";
            case AI_MODEL_ICON_REMOTE_RESPONSE_TOO_LARGE -> "外部图标响应不能超过 2 MiB。";
            case AI_MODEL_ICON_REMOTE_RESPONSE_INVALID -> "外部图标响应缺失或不是有效图片。";
            case AI_MODEL_ICON_STORAGE_UNAVAILABLE -> "模型图标对象存储暂时不可用。";
            case AI_MODEL_ICON_PUBLIC_ID_INVALID -> "模型图标公共 ID 格式无效。";
            default -> "模型图标请求参数无效。";
        };
    }

    /**
     * 是从包装异常中提取直接诊断异常与最深根因的不可变快照，并防止异常 cause 环造成无限遍历。
     */
    private record DiagnosticChain(
            String exceptionType,
            String exceptionMessage,
            String rootCauseType,
            String rootCauseMessage) {

        private static DiagnosticChain from(Throwable wrapper) {
            Throwable diagnostic = wrapper.getCause() == null ? wrapper : wrapper.getCause();
            Throwable root = deepestCause(diagnostic);
            return new DiagnosticChain(
                    diagnostic.getClass().getName(),
                    diagnostic.getMessage(),
                    root.getClass().getName(),
                    root.getMessage());
        }

        private static Throwable deepestCause(Throwable failure) {
            Set<Throwable> visited =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            Throwable current = failure;
            while (current.getCause() != null
                    && visited.add(current)
                    && !visited.contains(current.getCause())) {
                current = current.getCause();
            }
            return current;
        }
    }
}
