package com.example.temperate.service.admin.mailinspection.oauth;

import java.util.List;

/**
 * 保存 OAuth HTTP 响应的白名单字段，明确排除 error_description 与原始响应体。
 */
record OAuthTokenHttpResponse(
        int httpStatus,
        String accessToken,
        String error,
        List<Integer> errorCodes,
        Long retryAfterSeconds) {

    OAuthTokenHttpResponse {
        errorCodes = errorCodes == null ? List.of() : List.copyOf(errorCodes);
    }

    static OAuthTokenHttpResponse success(String accessToken) {
        return new OAuthTokenHttpResponse(200, accessToken, null, List.of(), null);
    }

    static OAuthTokenHttpResponse error(
            int status,
            String error,
            List<Integer> errorCodes,
            Long retryAfterSeconds) {
        return new OAuthTokenHttpResponse(
                status, null, error, errorCodes, retryAfterSeconds);
    }

    boolean isSuccessfulStatus() {
        return httpStatus >= 200 && httpStatus < 300;
    }

    @Override
    public String toString() {
        return "OAuthTokenHttpResponse[httpStatus="
                + httpStatus
                + ",body=protected]";
    }
}
