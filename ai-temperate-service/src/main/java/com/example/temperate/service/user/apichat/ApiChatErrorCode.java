package com.example.temperate.service.user.apichat;

/**
 * 该枚举是来稳定区分公开 Chat Completions 的协议、模型、授权、并发、额度与上游失败，供 HTTP 和 SSE 使用同一机器码。
 */
public enum ApiChatErrorCode {
    INVALID_API_KEY(401, "authentication_error", "invalid_api_key"),
    INVALID_REQUEST(400, "invalid_request_error", "invalid_request"),
    STREAM_REQUIRED(400, "invalid_request_error", "stream_required"),
    CONTEXT_LENGTH_EXCEEDED(400, "invalid_request_error", "context_length_exceeded"),
    MODEL_NOT_FOUND(404, "invalid_request_error", "model_not_found"),
    MODEL_NOT_ALLOWED(403, "permission_error", "model_not_allowed"),
    ACCOUNT_NOT_ACTIVE(403, "permission_error", "account_not_active"),
    API_KEY_LIMIT_EXCEEDED(429, "rate_limit_error", "api_key_concurrency_exceeded"),
    ACCOUNT_LIMIT_EXCEEDED(429, "rate_limit_error", "account_concurrency_exceeded"),
    GLOBAL_LIMIT_EXCEEDED(429, "rate_limit_error", "global_concurrency_exceeded"),
    INSUFFICIENT_QUOTA(429, "insufficient_quota", "insufficient_quota"),
    INFRASTRUCTURE_UNAVAILABLE(503, "server_error", "infrastructure_unavailable"),
    UPSTREAM_PROTOCOL_ERROR(502, "server_error", "upstream_protocol_error"),
    UPSTREAM_UNAVAILABLE(503, "server_error", "upstream_unavailable");

    private final int status;
    private final String type;
    private final String code;

    ApiChatErrorCode(int status, String type, String code) {
        this.status = status;
        this.type = type;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }
}
