package com.example.temperate.web.auth.device.exception;

import org.springframework.http.HttpStatus;

/**
 * 表示全局设备封禁过滤器可以直接转换为 HTTP 响应的受控异常。
 *
 * <p>过滤器运行在 Controller 之前，无法依赖普通参数校验异常路径，因此使用该类型承载稳定的状态码、错误码和外部文案。</p>
 */
public final class GlobalDeviceBlockException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private GlobalDeviceBlockException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static GlobalDeviceBlockException invalidInput() {
        return new GlobalDeviceBlockException(
                HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求参数不正确。");
    }

    public static GlobalDeviceBlockException blocked() {
        return new GlobalDeviceBlockException(
                HttpStatus.TOO_MANY_REQUESTS, "DEVICE_BLOCKED", "操作过于频繁，请稍后再试。");
    }

    public static GlobalDeviceBlockException unavailable() {
        return new GlobalDeviceBlockException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "INFRASTRUCTURE_UNAVAILABLE",
                "服务暂时不可用，请稍后再试。");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
