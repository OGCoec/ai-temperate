package com.example.temperate.web.admin.api;

import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryErrorCode;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryException;
import com.example.temperate.web.admin.controller.AdminCliProxyModelDiscoveryController;
import com.example.temperate.web.auth.api.ApiErrorResponse;
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
 * 将 CLIProxyAPI 模型发现的受控失败映射为不泄露密钥、响应体或内部异常的管理员错误响应。
 */
@RestControllerAdvice(assignableTypes = AdminCliProxyModelDiscoveryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AdminCliProxyModelDiscoveryExceptionHandler {

    private final Clock clock;
    private final AdminExceptionLogger exceptionLogger;

    public AdminCliProxyModelDiscoveryExceptionHandler(
            Clock clock,
            AdminExceptionLogger exceptionLogger) {
        this.clock = Objects.requireNonNull(clock);
        this.exceptionLogger = Objects.requireNonNull(exceptionLogger);
    }

    @ExceptionHandler(CliProxyModelDiscoveryException.class)
    public ResponseEntity<ApiErrorResponse> handle(
            CliProxyModelDiscoveryException exception) {
        HttpStatus status = status(exception.code());
        exceptionLogger.logKnown(
                "admin_cli_proxy_model_discovery_rejected",
                exception.code().name(),
                status,
                exception);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ApiErrorResponse(
                        exception.code().name(),
                        message(exception.code()),
                        clock.instant()));
    }

    private static HttpStatus status(CliProxyModelDiscoveryErrorCode code) {
        return switch (code) {
            case CLI_PROXY_MODEL_DISCOVERY_DISABLED,
                    CLI_PROXY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CLI_PROXY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CLI_PROXY_AUTH_FAILED,
                    CLI_PROXY_REQUEST_FAILED,
                    CLI_PROXY_RESPONSE_INVALID -> HttpStatus.BAD_GATEWAY;
        };
    }

    private static String message(CliProxyModelDiscoveryErrorCode code) {
        return switch (code) {
            case CLI_PROXY_MODEL_DISCOVERY_DISABLED ->
                    "CLIProxyAPI 模型发现功能尚未启用。";
            case CLI_PROXY_UNAVAILABLE ->
                    "CLIProxyAPI 当前不可用，请确认本机服务已经启动。";
            case CLI_PROXY_TIMEOUT ->
                    "读取 CLIProxyAPI 模型列表超时，请稍后重试。";
            case CLI_PROXY_AUTH_FAILED ->
                    "CLIProxyAPI 认证失败，请检查服务端密钥配置。";
            case CLI_PROXY_REQUEST_FAILED ->
                    "CLIProxyAPI 模型列表请求失败。";
            case CLI_PROXY_RESPONSE_INVALID ->
                    "CLIProxyAPI 返回了无法接受的模型列表。";
        };
    }
}
