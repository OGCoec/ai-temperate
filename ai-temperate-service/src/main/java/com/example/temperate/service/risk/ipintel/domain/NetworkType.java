package com.example.temperate.service.risk.ipintel.domain;

/**
 * 统一不同供应商对网络接入类型、代理和匿名网络的分类。
 */
public enum NetworkType {
    RESIDENTIAL,
    MOBILE,
    BUSINESS,
    UNKNOWN,
    VPN,
    DATA_CENTER,
    WEB_PROXY,
    PUBLIC_PROXY,
    TOR
}
