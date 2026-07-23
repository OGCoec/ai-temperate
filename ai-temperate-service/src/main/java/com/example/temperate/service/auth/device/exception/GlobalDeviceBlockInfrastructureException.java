package com.example.temperate.service.auth.device.exception;

/**
 * 表示全局设备封禁状态读取或写入时发生基础设施异常。
 *
 * <p>该异常只表达 Redis 等外部状态不可用，不携带原始设备标识，避免日志或响应中泄露可被重放的客户端材料。</p>
 */
public final class GlobalDeviceBlockInfrastructureException extends RuntimeException {

    public GlobalDeviceBlockInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
