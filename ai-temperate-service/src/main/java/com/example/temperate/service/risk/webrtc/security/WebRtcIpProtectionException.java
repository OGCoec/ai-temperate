package com.example.temperate.service.risk.webrtc.security;

/**
 * 表示 WebRTC IP 密文无法通过格式、密钥或 AAD 认证，调用方只能清除状态并重新校验。
 */
public final class WebRtcIpProtectionException extends RuntimeException {

    public WebRtcIpProtectionException() {
        super("WebRTC IP protection failed.");
    }

    public WebRtcIpProtectionException(Throwable cause) {
        super("WebRTC IP protection failed.", cause);
    }
}
