package com.example.temperate.service.risk.domain;

/**
 * 区分普通用户和管理员网络风险状态，使 Cookie、Redis Key 与 Challenge 引用不能跨作用域复用。
 */
public enum RiskScope {
    USER,
    ADMIN
}
