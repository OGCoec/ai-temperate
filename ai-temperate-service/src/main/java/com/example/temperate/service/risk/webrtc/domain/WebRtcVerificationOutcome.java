package com.example.temperate.service.risk.webrtc.domain;

/**
 * 定义 WebRTC 异步门禁的受控结果，供 Service、Controller 与拦截器共享稳定分支语义。
 */
public enum WebRtcVerificationOutcome {
    VERIFIED,
    VERIFICATION_PENDING,
    VERIFICATION_REQUIRED,
    VERIFICATION_FAILED,
    VERIFICATION_TIMEOUT,
    IP_FAMILY_INCOMPLETE,
    IP_MISMATCH,
    NETWORK_CHANGED,
    STALE_REPORT,
    STATE_INVALID
}
