package com.example.temperate.service.risk.ip2location.domain;

/**
 * 标识导入凭据所属的 IP2Location 套餐，仅作为管理元数据，不在代码中推断供应商额度。
 */
public enum Ip2LocationPlanType {
    FREE,
    STARTER,
    PLUS,
    SECURITY,
    SECURITY_TRIAL,
    CUSTOM
}
