package com.example.temperate.service.user.apikey.management;

/**
 * 该异常是来携带不含 Key 原文和内部摘要的受控管理与调用记录错误；内部 cause 只供诊断，禁止把数据库或加密异常正文直接返回客户端。
 */
public final class ApiKeyManagementException extends RuntimeException {

    private final ApiKeyManagementErrorCode code;

    public ApiKeyManagementException(ApiKeyManagementErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiKeyManagementException(
            ApiKeyManagementErrorCode code,
            String message,
            Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ApiKeyManagementErrorCode code() {
        return code;
    }
}
