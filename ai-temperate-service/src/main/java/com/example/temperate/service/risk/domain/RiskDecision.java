package com.example.temperate.service.risk.domain;

/**
 * 表示一次请求实时计算得到的网络风险处置结果。
 */
public enum RiskDecision {
    ALLOW,
    CHALLENGE,
    BLOCK
}
