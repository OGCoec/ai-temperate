package com.example.temperate.service.risk.preauth.domain;

/**
 * 表示 WebRTC 两字段的原子写入结果，用于区分更新、并发保留和网络变化。
 */
public enum PreAuthWebRtcWriteResult {
    UPDATED,
    VERIFIED_PRESERVED,
    FAILURE_PRESERVED,
    NETWORK_CHANGED,
    PREAUTH_UNAVAILABLE
}
