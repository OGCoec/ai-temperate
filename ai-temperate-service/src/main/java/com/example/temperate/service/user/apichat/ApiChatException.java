package com.example.temperate.service.user.apichat;

/**
 * 该异常是来携带可安全返回的 OpenAI 错误码、字段和受控消息，禁止附带 8317 原始正文、Key、IP 或模型内容。
 */
public final class ApiChatException extends RuntimeException {

    private final ApiChatErrorCode code;
    private final String parameter;

    public ApiChatException(ApiChatErrorCode code, String message, String parameter) {
        this(code, message, parameter, null);
    }

    public ApiChatException(
            ApiChatErrorCode code,
            String message,
            String parameter,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.parameter = parameter;
    }

    public ApiChatErrorCode code() {
        return code;
    }

    public String parameter() {
        return parameter;
    }

    public static ApiChatException invalid(String message, String parameter) {
        return new ApiChatException(ApiChatErrorCode.INVALID_REQUEST, message, parameter);
    }
}
