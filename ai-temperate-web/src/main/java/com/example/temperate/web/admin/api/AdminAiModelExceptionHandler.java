package com.example.temperate.web.admin.api;

import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import com.example.temperate.web.admin.controller.AdminAiModelController;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import java.time.Clock;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将管理员 AI 模型受控异常映射为稳定 HTTP 状态和不含内部数据的错误体。
 *
 * <p>该处理器只覆盖 AI 模型 Controller，不改变既有管理员认证异常与 Cookie 清理语义。</p>
 */
@RestControllerAdvice(assignableTypes = AdminAiModelController.class)
public final class AdminAiModelExceptionHandler {

    private final Clock clock;
    private final AdminExceptionLogger exceptionLogger;

    public AdminAiModelExceptionHandler(
            Clock clock,
            AdminExceptionLogger exceptionLogger) {
        this.clock = Objects.requireNonNull(clock);
        this.exceptionLogger = Objects.requireNonNull(exceptionLogger);
    }

    @ExceptionHandler(AdminAiModelException.class)
    public ResponseEntity<ApiErrorResponse> handle(AdminAiModelException exception) {
        HttpStatus httpStatus = status(exception.code());
        exceptionLogger.logKnown(
                "admin_ai_model_rejected",
                exception.code().name(),
                httpStatus,
                exception);
        return ResponseEntity.status(httpStatus)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ApiErrorResponse(
                        exception.code().name(),
                        message(exception.code()),
                        clock.instant()));
    }

    private static HttpStatus status(AdminAiModelErrorCode code) {
        return switch (code) {
            case AI_MODEL_NOT_FOUND, AI_MODEL_ICON_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AI_MODEL_NAME_CONFLICT, AI_MODEL_VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case AI_MODEL_VERSION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String message(AdminAiModelErrorCode code) {
        return switch (code) {
            case AI_MODEL_NOT_FOUND -> "AI 模型不存在。";
            case AI_MODEL_ICON_NOT_FOUND -> "选择的模型图标不存在。";
            case AI_MODEL_ICON_PUBLIC_ID_INVALID -> "模型图标公共 ID 格式无效。";
            case AI_MODEL_NAME_CONFLICT -> "AI 模型名称已经存在。";
            case AI_MODEL_VERSION_CONFLICT -> "AI 模型已经被其他请求修改，请刷新后重试。";
            case AI_MODEL_VERSION_REQUIRED -> "必须提供有效的 AI 模型版本。";
            case AI_MODEL_PATCH_INVALID -> "AI 模型修改内容无效。";
            case AI_MODEL_PUBLIC_ID_INVALID -> "AI 模型公共 ID 格式无效。";
            case AI_MODEL_CAPABILITY_INVALID -> "AI 模型能力代码不受支持。";
            case AI_MODEL_CAPABILITY_DUPLICATED -> "AI 模型能力代码不能重复。";
            case AI_MODEL_BATCH_ID_DUPLICATED -> "批量请求中的 AI 模型 ID 不能重复。";
            default -> "AI 模型请求参数无效。";
        };
    }
}
