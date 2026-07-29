package com.example.temperate.service.admin.aimodel.discovery.exception;

/**
 * 定义 CLIProxyAPI 管理员模型发现可以安全映射到 HTTP 的稳定错误码。
 */
public enum CliProxyModelDiscoveryErrorCode {
    CLI_PROXY_MODEL_DISCOVERY_DISABLED,
    CLI_PROXY_UNAVAILABLE,
    CLI_PROXY_TIMEOUT,
    CLI_PROXY_AUTH_FAILED,
    CLI_PROXY_REQUEST_FAILED,
    CLI_PROXY_RESPONSE_INVALID
}
