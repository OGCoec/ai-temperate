package com.example.temperate.web.user.avatar.api;

import com.example.temperate.service.user.avatar.UserAvatarErrorCode;
import com.example.temperate.service.user.avatar.UserAvatarException;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.example.temperate.web.user.avatar.controller.CurrentUserAvatarController;
import java.time.Clock;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把普通用户头像受控异常映射为稳定 HTTP 状态和不泄露 OSS 内部信息的错误体。
 */
@RestControllerAdvice(assignableTypes = CurrentUserAvatarController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class UserAvatarExceptionHandler {

    private final Clock clock;

    public UserAvatarExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(UserAvatarException.class)
    public ResponseEntity<ApiErrorResponse> handle(UserAvatarException exception) {
        return ResponseEntity.status(status(exception.code()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ApiErrorResponse(
                        exception.code().name(),
                        exception.getMessage(),
                        clock.instant()));
    }

    private static HttpStatus status(UserAvatarErrorCode code) {
        return switch (code) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case TEMP_OBJECT_NOT_FOUND -> HttpStatus.GONE;
            case INVALID_IMAGE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PROFILE_UNAVAILABLE -> HttpStatus.NOT_FOUND;
            case STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case PERSISTENCE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
