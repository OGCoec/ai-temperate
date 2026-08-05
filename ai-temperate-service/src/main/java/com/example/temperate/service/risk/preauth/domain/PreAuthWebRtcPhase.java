package com.example.temperate.service.risk.preauth.domain;

/**
 * 表示 PreAuth 中 WebRTC 异步门禁的持久化阶段，用于区分临时放行、已验证和已阻断状态。
 */
public enum PreAuthWebRtcPhase {
    REQUIRED,
    PENDING,
    VERIFIED,
    FAILED
}
