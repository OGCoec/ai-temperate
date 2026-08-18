package com.example.temperate.service.user.apichat;

/**
 * 该异常是来携带可安全返回的 OpenAI 错误码、字段和受控消息，禁止附带 8317 原始正文、Key、IP 或模型内容。
 */
public final class ApiChatException extends RuntimeException {

    private final ApiChatErrorCode code;
    private final String parameter;
    private final ValidationReason validationReason;

    /**
     * 该枚举是来为公开推理请求提供固定低基数的校验拒绝原因，日志只能记录这些枚举值而不能记录客户端原始取值。
     */
    public enum ValidationReason {
        UNSPECIFIED,
        WRONG_JSON_TYPE,
        BELOW_MINIMUM,
        OUTSIDE_MODEL_LIMIT
    }

    public ApiChatException(ApiChatErrorCode code, String message, String parameter) {
        this(code, message, parameter, null, ValidationReason.UNSPECIFIED);
    }

    public ApiChatException(
            ApiChatErrorCode code,
            String message,
            String parameter,
            Throwable cause) {
        this(code, message, parameter, cause, ValidationReason.UNSPECIFIED);
    }

    private ApiChatException(
            ApiChatErrorCode code,
            String message,
            String parameter,
            Throwable cause,
            ValidationReason validationReason) {
        super(message, cause);
        this.code = code;
        this.parameter = parameter;
        this.validationReason = validationReason == null
                ? ValidationReason.UNSPECIFIED : validationReason;
    }

    public ApiChatErrorCode code() {
        return code;
    }

    public String parameter() {
        return parameter;
    }

    public ValidationReason validationReason() {
        return validationReason;
    }

    public static ApiChatException invalid(String message, String parameter) {
        return new ApiChatException(ApiChatErrorCode.INVALID_REQUEST, message, parameter);
    }

    public static ApiChatException invalid(
            String message,
            String parameter,
            ValidationReason validationReason) {
        return new ApiChatException(
                ApiChatErrorCode.INVALID_REQUEST,
                message,
                parameter,
                null,
                validationReason);
    }
}
