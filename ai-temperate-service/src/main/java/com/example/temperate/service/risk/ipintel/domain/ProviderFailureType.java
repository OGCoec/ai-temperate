package com.example.temperate.service.risk.ipintel.domain;

/**
 * 归一化外部供应商失败类型，防止业务层依赖供应商专有错误文案。
 */
public enum ProviderFailureType {
    NONE,
    INVALID_INPUT,
    NO_CREDENTIAL,
    AUTHENTICATION,
    QUOTA_EXHAUSTED,
    HTTP_STATUS,
    BUSINESS_RESPONSE,
    EMPTY_RESPONSE,
    TIMEOUT,
    UNAVAILABLE
}
