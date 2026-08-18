package com.example.temperate.service.risk.preauth.domain;

/**
 * 记录 WebRTC 门禁失败的受控原因，使后续请求可以返回稳定错误而不暴露原始网络数据。
 */
public enum PreAuthWebRtcFailureReason {
    NO_PUBLIC_CANDIDATE,
    IP_FAMILY_INCOMPLETE,
    IP_MISMATCH,
    START_TIMEOUT,
    REPORT_TIMEOUT
}
