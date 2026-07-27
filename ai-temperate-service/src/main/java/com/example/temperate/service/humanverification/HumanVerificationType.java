package com.example.temperate.service.humanverification;

/**
 * 定义服务端支持的人机验证策略类型，业务只能使用该稳定枚举选择实现，不能依赖 Spring Bean 名称。
 */
public enum HumanVerificationType {
    TURNSTILE,
    HCAPTCHA
}
