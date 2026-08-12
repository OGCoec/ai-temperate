package com.example.temperate.service.auth.device.service;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.time.Duration;

/**
 * 提供基于原始设备安装标识或既有 HMAC 摘要的全局认证入口封禁查询能力。
 *
 * <p>MVC 调用方传入原始 UUID，Voice 握手只传递 Ticket 中的受保护摘要；两条路径必须命中同一
 * Redis Key。该接口不负责决定哪些 HTTP 或 WebSocket 路径需要拦截。</p>
 */
public interface GlobalDeviceBlockService {

    Duration remainingBlockTtl(String deviceInstallationId);

    Duration remainingBlockTtlByDigest(HmacIdentifier globalDeviceBlockDigest);
}
