package com.example.temperate.web.user.aimodel.api;

import com.example.temperate.service.user.aimodel.exception.UserAiModelCatalogErrorCode;
import com.example.temperate.service.user.aimodel.exception.UserAiModelCatalogException;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.example.temperate.web.user.aimodel.controller.UserAiModelController;
import java.time.Clock;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把普通用户模型目录受控异常映射为稳定 HTTP 状态和不含内部标识的错误响应。
 */
@RestControllerAdvice(assignableTypes = UserAiModelController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class UserAiModelCatalogExceptionHandler {

    private final Clock clock;

    public UserAiModelCatalogExceptionHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @ExceptionHandler(UserAiModelCatalogException.class)
    public ResponseEntity<ApiErrorResponse> handle(UserAiModelCatalogException exception) {
        return ResponseEntity.status(status(exception.code()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ApiErrorResponse(
                        exception.code().name(),
                        exception.getMessage(),
                        clock.instant()));
    }

    private static HttpStatus status(UserAiModelCatalogErrorCode code) {
        return switch (code) {
            case AI_MODEL_PAGE_INVALID, AI_MODEL_PUBLIC_ID_INVALID ->
                    HttpStatus.BAD_REQUEST;
            case AI_MODEL_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
    }
}
