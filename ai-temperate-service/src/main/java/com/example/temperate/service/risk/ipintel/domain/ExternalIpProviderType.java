package com.example.temperate.service.risk.ipintel.domain;

/**
 * 定义外部 IP 情报提供者的稳定业务类型，避免用 Spring Bean 名称决定降级顺序。
 */
public enum ExternalIpProviderType {
    IP2LOCATION,
    IPING
}
