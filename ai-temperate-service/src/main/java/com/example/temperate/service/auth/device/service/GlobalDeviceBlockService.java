package com.example.temperate.service.auth.device.service;

import java.time.Duration;

/**
 * 提供基于设备安装标识的全局认证入口封禁查询能力。
 *
 * <p>调用方只传入原始客户端设备标识，服务内部负责格式校验、HMAC 保护和 Redis Key 访问；接口不负责决定哪些 HTTP 路径需要被拦截。</p>
 */
public interface GlobalDeviceBlockService {

    Duration remainingBlockTtl(String deviceInstallationId);
}
