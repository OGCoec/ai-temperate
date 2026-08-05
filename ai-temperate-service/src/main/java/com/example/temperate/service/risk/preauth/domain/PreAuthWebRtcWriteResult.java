package com.example.temperate.service.risk.preauth.domain;

/**
 * 表示 WebRTC generation 状态机的原子写入结果，用于区分更新、并发保留、超时和网络变化。
 */
public enum PreAuthWebRtcWriteResult {
    UPDATED,
    VERIFIED_PRESERVED,
    FAILURE_PRESERVED,
    DEADLINE_EXPIRED,
    STALE_GENERATION,
    NETWORK_CHANGED,
    PREAUTH_UNAVAILABLE
}
