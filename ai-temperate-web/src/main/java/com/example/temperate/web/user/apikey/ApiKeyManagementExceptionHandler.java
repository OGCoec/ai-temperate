package com.example.temperate.web.user.apikey;

import com.example.temperate.service.user.apikey.bloom.ApiKeyBloomUnavailableException;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import java.time.Clock;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 该处理器是来把 API Key 管理的受控异常映射为 400、404、409、412、428 或 503，并保证错误体不包含原始 Key、摘要或数据库细节。
 */
@RestControllerAdvice(assignableTypes = CurrentUserApiKeyController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ApiKeyManagementExceptionHandler {

    private final Clock clock;

    public ApiKeyManagementExceptionHandler(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @ExceptionHandler(ApiKeyManagementException.class)
    public ResponseEntity<ApiErrorResponse> handle(ApiKeyManagementException exception) {
        HttpStatus status = switch (exception.code()) {
            case FEATURE_DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
            case API_KEY_CREATE_COORDINATION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case API_KEY_CREATE_IN_PROGRESS, API_KEY_CREATE_ALREADY_COMPLETED ->
                    HttpStatus.CONFLICT;
            case API_KEY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VERSION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case VERSION_CONFLICT -> HttpStatus.PRECONDITION_FAILED;
            default -> HttpStatus.BAD_REQUEST;
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate());
        if (exception.code() == ApiKeyManagementErrorCode.API_KEY_CREATE_IN_PROGRESS) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(new ApiErrorResponse(
                exception.code().name(),
                message(exception.code()),
                clock.instant()));
    }

    @ExceptionHandler(ApiKeyBloomUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleBloomUnavailable(
            ApiKeyBloomUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ApiErrorResponse(
                        "API_KEY_INFRASTRUCTURE_UNAVAILABLE",
                        "API Key 基础设施暂时不可用，请稍后重试。",
                        clock.instant()));
    }

    private static String message(ApiKeyManagementErrorCode code) {
        return switch (code) {
            case FEATURE_DISABLED -> "API Key 功能暂未启用。";
            case IDEMPOTENCY_KEY_INVALID -> "Idempotency-Key 必须是规范小写 UUIDv4。";
            case API_KEY_CREATE_IN_PROGRESS -> "相同的 API Key 创建请求正在处理中，请稍后手动重试。";
            case API_KEY_CREATE_ALREADY_COMPLETED ->
                    "该创建请求已经完成，完整 API Key 无法再次获取，请刷新列表；需要新凭证时请先撤销后重新创建。";
            case API_KEY_CREATE_COORDINATION_UNAVAILABLE ->
                    "API Key 创建协调暂时不可用，请稍后重试。";
            case API_KEY_NOT_FOUND -> "API Key 不存在。";
            case VERSION_REQUIRED -> "必须提供 API Key 的 If-Match。";
            case VERSION_INVALID -> "API Key 的 If-Match 格式无效。";
            case VERSION_CONFLICT -> "API Key 已被其他请求修改，请刷新后重试。";
            case MODEL_NOT_FOUND_OR_DISABLED -> "选择的模型不存在或当前不可用。";
            case PUBLIC_ID_INVALID -> "API Key 公共 ID 格式无效。";
            case CURSOR_INVALID -> "API Key 游标格式无效。";
            default -> "API Key 请求参数无效。";
        };
    }
}
