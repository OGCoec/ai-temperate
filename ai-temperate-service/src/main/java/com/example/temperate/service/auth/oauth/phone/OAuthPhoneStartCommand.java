package com.example.temperate.service.auth.oauth.phone;

import com.example.temperate.service.auth.oauth.flow.OAuthFlowAccess;

/**
 * 表示当前 OAuth Flow 选择并锁定一个待验证手机号的服务端命令。
 */
public record OAuthPhoneStartCommand(
        OAuthFlowAccess oauthAccess,
        String countryIso2,
        String phoneNumber) {
}
