package com.example.temperate.service.admin.aimodel.discovery.exception;

import java.util.Objects;

/**
 * 表示 CLIProxyAPI 模型发现边界内不携带密钥、响应体或内部网络细节的受控失败。
 */
public final class CliProxyModelDiscoveryException extends RuntimeException {

    private final CliProxyModelDiscoveryErrorCode code;

    public CliProxyModelDiscoveryException(
            CliProxyModelDiscoveryErrorCode code,
            String message) {
        super(message);
        this.code = Objects.requireNonNull(code);
    }

    public CliProxyModelDiscoveryErrorCode code() {
        return code;
    }
}
