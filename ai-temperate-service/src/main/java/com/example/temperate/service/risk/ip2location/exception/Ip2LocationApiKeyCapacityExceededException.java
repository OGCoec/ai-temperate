package com.example.temperate.service.risk.ip2location.exception;

/**
 * 表示 IP2Location 加密凭据池已经达到全局容量上限，供管理端映射为可恢复的冲突响应。
 *
 * <p>异常不携带 Key ID、密文或明文凭据，避免容量竞争路径泄露敏感输入。</p>
 */
public final class Ip2LocationApiKeyCapacityExceededException extends RuntimeException {

    public Ip2LocationApiKeyCapacityExceededException() {
        super("IP2Location API key capacity has been reached.");
    }
}
