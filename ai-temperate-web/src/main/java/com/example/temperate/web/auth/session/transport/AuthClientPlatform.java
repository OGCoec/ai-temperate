package com.example.temperate.web.auth.session.transport;

/**
 * 认证凭据传输协议的平台选择枚举。
 *
 * <p>用途：区分 H5 Cookie 协议与 Android 显式 Header/会话端点请求体协议；该值只控制解析位置，不代表已认证身份。</p>
 */
public enum AuthClientPlatform {
    H5,
    ANDROID;

    public static AuthClientPlatform fromHeader(String value) {
        return "ANDROID".equalsIgnoreCase(value) ? ANDROID : H5;
    }
}
