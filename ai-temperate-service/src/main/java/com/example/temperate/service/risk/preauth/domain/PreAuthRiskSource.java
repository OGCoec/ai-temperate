package com.example.temperate.service.risk.preauth.domain;

/**
 * 标识当前 PreAuth 信用分实际采用的风险来源，避免把地理信息来源误当成信用分供应商。
 */
public enum PreAuthRiskSource {
    IP2LOCATION,
    IPING,
    DEFAULT
}
