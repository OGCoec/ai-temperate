package com.example.temperate.service.risk.webrtc.domain;

/**
 * 定义 WebRTC 校验状态机的受控结果，避免将空候选错误描述为 IP 泄漏。
 */
public enum WebRtcVerificationOutcome {
    VERIFIED,
    VERIFICATION_REQUIRED,
    VERIFICATION_FAILED,
    IP_MISMATCH,
    NETWORK_CHANGED
}
