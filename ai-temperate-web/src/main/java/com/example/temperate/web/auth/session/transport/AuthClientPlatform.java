package com.example.temperate.web.auth.session.transport;

/**
 * 认证与会话凭据传输协议的平台选择枚举。
 *
 * <p>本枚举用于在 Web 入口层确定客户端凭据的物理承载方式（Cookie 还是显式 Header/JSON Body），
 * 不作为用户身份认证或资源级权限判定的依据。H5 平台绑定浏览器 Cookie 会话；Android 与微信小程序
 * 等原生/受限运行时绑定显式令牌传输，服务端严禁对其下发 Cookie。</p>
 */
public enum AuthClientPlatform {
    H5(false),
    ANDROID(true),
    WECHAT_MINI_PROGRAM(true);

    private final boolean explicitTokenTransport;

    AuthClientPlatform(boolean explicitTokenTransport) {
        this.explicitTokenTransport = explicitTokenTransport;
    }

    /**
     * 判断当前平台是否使用显式令牌传输（Header/Body）而非浏览器 Cookie。
     *
     * @return 若使用显式传输则返回 {@code true}，若为 H5 Cookie 传输则返回 {@code false}
     */
    public boolean usesExplicitTokenTransport() {
        return explicitTokenTransport;
    }

    /**
     * 根据请求头解析客户端平台类型。
     *
     * @param value 客户端传入的 X-Client-Platform 请求头值
     * @return 解析得到的平台枚举实例；若为空或空白则默认返回 {@link #H5}
     * @throws IllegalArgumentException 当传入非空但不支持的平台字符串时抛出
     */
    public static AuthClientPlatform fromHeader(String value) {
        if (value == null || value.isBlank()) {
            return H5;
        }
        String normalized = value.trim();
        if ("ANDROID".equalsIgnoreCase(normalized)) {
            return ANDROID;
        }
        if ("WECHAT_MINI_PROGRAM".equalsIgnoreCase(normalized)) {
            return WECHAT_MINI_PROGRAM;
        }
        if ("H5".equalsIgnoreCase(normalized)) {
            return H5;
        }
        throw new IllegalArgumentException("Unsupported client platform: " + value);
    }
}
