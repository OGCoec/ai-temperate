package com.example.temperate.service.user.voice.diagnostic;

import java.util.Objects;

/**
 * 承载一次语音 WebSocket 连接跨线程、跨模块使用的脱敏诊断关联信息。
 *
 * <p>该上下文只包含服务端生成的追踪标识和经过白名单归一化的边缘 Ray，不能放入 Ticket、
 * Cookie、用户、设备、IP 或音频信息；它只服务于日志关联，不参与任何认证或业务决策。</p>
 *
 * @param traceId 服务端为本次公开握手生成的追踪标识
 * @param edgeRay 已归一化为安全标识、ABSENT 或 INVALID 的边缘 Ray
 */
public record VoiceDiagnosticContext(String traceId, String edgeRay) {

    public static final String ATTRIBUTE =
            VoiceDiagnosticContext.class.getName() + ".context";

    public VoiceDiagnosticContext {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(edgeRay, "edgeRay must not be null");
        if (traceId.isBlank() || traceId.length() > 64
                || edgeRay.isBlank() || edgeRay.length() > 128) {
            throw new IllegalArgumentException("Voice diagnostic context is invalid.");
        }
    }
}
