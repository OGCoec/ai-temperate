package com.example.temperate.service.auth.oauth.phone;

import com.example.temperate.service.auth.oauth.flow.OAuthFlowAccess;

/**
 * 表示 OAuth 外层流程与其绑定的手机验证码子流程访问材料。
 */
public record OAuthPhoneAccess(
        OAuthFlowAccess oauthAccess,
        String rawPhoneFlowToken,
        String challengeHandle) {
}
