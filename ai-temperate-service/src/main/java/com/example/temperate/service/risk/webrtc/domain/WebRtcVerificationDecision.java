package com.example.temperate.service.risk.webrtc.domain;

import java.util.List;
import java.util.Objects;

/**
 * 携带服务端计算出的 WebRTC 三态及可展示 IP 集合，不接收客户端提交的匹配结论。
 */
public record WebRtcVerificationDecision(
        WebRtcVerificationOutcome outcome,
        Boolean webRtcStatus,
        List<String> webRtcIps) {

    public WebRtcVerificationDecision {
        Objects.requireNonNull(outcome);
        webRtcIps = webRtcIps == null ? List.of() : List.copyOf(webRtcIps);
        boolean valid = switch (outcome) {
            case VERIFIED -> Boolean.TRUE.equals(webRtcStatus);
            case VERIFICATION_REQUIRED, NETWORK_CHANGED -> webRtcStatus == null;
            case VERIFICATION_FAILED -> Boolean.FALSE.equals(webRtcStatus)
                    && webRtcIps.isEmpty();
            case IP_MISMATCH -> Boolean.FALSE.equals(webRtcStatus)
                    && !webRtcIps.isEmpty();
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "WebRTC decision state is inconsistent.");
        }
    }

    public static WebRtcVerificationDecision verified(List<String> ips) {
        return new WebRtcVerificationDecision(
                WebRtcVerificationOutcome.VERIFIED,
                Boolean.TRUE,
                ips);
    }

    public static WebRtcVerificationDecision required() {
        return new WebRtcVerificationDecision(
                WebRtcVerificationOutcome.VERIFICATION_REQUIRED,
                null,
                List.of());
    }

    public static WebRtcVerificationDecision failed() {
        return new WebRtcVerificationDecision(
                WebRtcVerificationOutcome.VERIFICATION_FAILED,
                Boolean.FALSE,
                List.of());
    }

    public static WebRtcVerificationDecision mismatch(List<String> ips) {
        return new WebRtcVerificationDecision(
                WebRtcVerificationOutcome.IP_MISMATCH,
                Boolean.FALSE,
                ips);
    }

    public static WebRtcVerificationDecision networkChanged() {
        return new WebRtcVerificationDecision(
                WebRtcVerificationOutcome.NETWORK_CHANGED,
                null,
                List.of());
    }
}
