package com.example.temperate.service.audit.access.domain;

/**
 * 表示可安全进入消息、数据库和指标边界的客户端 IP 派生值，不保留原始完整地址。
 */
public record ProtectedClientIp(
        int ipFamily,
        String ipPrefix,
        String ipHmac) {
}
