package com.example.temperate.web.edgeproxy;

/**
 * 表示边缘签名缺失、过期、被篡改或绑定到了非白名单外部主机。
 *
 * <p>异常消息只描述错误类别，禁止携带签名、Secret、Cookie 或请求体。</p>
 */
public final class EdgeProxyVerificationException extends RuntimeException {

    public EdgeProxyVerificationException(String message) {
        super(message);
    }
}
