package com.example.temperate.service.admin.mailinspection.oauth;

import java.util.Objects;

/**
 * 表示一次专用 OAuth 表单所需的两个敏感字段，并固定使用脱敏调试文本。
 */
record OAuthTokenRequest(String clientId, String refreshToken) {

    OAuthTokenRequest {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    }

    @Override
    public String toString() {
        return "OAuthTokenRequest[credentials=protected]";
    }
}
