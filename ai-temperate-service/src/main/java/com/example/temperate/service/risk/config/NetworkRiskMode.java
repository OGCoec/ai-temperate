package com.example.temperate.service.risk.config;

/**
 * 定义网络风险能力的部署阶段，避免未完成边缘签名和客户端迁移时直接阻断生产请求。
 */
public enum NetworkRiskMode {
    DISABLED,
    OBSERVE,
    ENFORCE
}
