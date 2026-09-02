package com.example.temperate.service.risk.webrtc.domain;

import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 携带服务端判定的 WebRTC 阶段、generation、截止时间和受控失败原因。
 *
 * <p>PENDING 的剩余毫秒数来自 Redis begin Lua，Controller 不使用 Java 节点时间推导权威窗口；
 * REQUIRED 仅携带触发后台任务所需的 generation，业务请求仍会放行。</p>
 */
public record WebRtcVerificationDecision(
        WebRtcVerificationOutcome outcome,
        long probeGeneration,
        Instant pendingUntil,
        long pendingRemainingMillis,
        PreAuthWebRtcFailureReason failureReason,
        List<String> webRtcIps) {

    public WebRtcVerificationDecision {
        Objects.requireNonNull(outcome);
        webRtcIps = webRtcIps == null ? List.of() : List.copyOf(webRtcIps);
        if (pendingRemainingMillis < 0) {
            throw new IllegalArgumentException("WebRTC remaining time cannot be negative.");
        }
        boolean stateful = switch (outcome) {
            case VERIFIED, VERIFICATION_PENDING, VERIFICATION_REQUIRED,
                    VERIFICATION_FAILED, VERIFICATION_TIMEOUT,
                    IP_FAMILY_INCOMPLETE, IP_MISMATCH -> true;
            case NETWORK_CHANGED, OAUTH_ATTEMPT_REQUIRED,
                    STALE_REPORT, STATE_INVALID -> false;
        };
        if (stateful && probeGeneration <= 0) {
            throw new IllegalArgumentException("WebRTC generation is required.");
        }
        boolean valid = switch (outcome) {
            case VERIFIED -> pendingUntil == null && pendingRemainingMillis == 0
                    && failureReason == null && !webRtcIps.isEmpty();
            case VERIFICATION_PENDING -> pendingUntil != null
                    && failureReason == null && webRtcIps.isEmpty();
            case VERIFICATION_REQUIRED -> pendingUntil != null
                    && pendingRemainingMillis == 0
                    && failureReason == null && webRtcIps.isEmpty();
            case VERIFICATION_FAILED -> pendingUntil == null
                    && pendingRemainingMillis == 0
                    && failureReason == PreAuthWebRtcFailureReason.NO_PUBLIC_CANDIDATE
                    && webRtcIps.isEmpty();
            case VERIFICATION_TIMEOUT -> pendingUntil == null
                    && pendingRemainingMillis == 0
                    && (failureReason == PreAuthWebRtcFailureReason.START_TIMEOUT
                    || failureReason == PreAuthWebRtcFailureReason.REPORT_TIMEOUT)
                    && webRtcIps.isEmpty();
            case IP_MISMATCH -> pendingUntil == null
                    && pendingRemainingMillis == 0
                    && failureReason == PreAuthWebRtcFailureReason.IP_MISMATCH
                    && !webRtcIps.isEmpty();
            case IP_FAMILY_INCOMPLETE -> pendingUntil == null
                    && pendingRemainingMillis == 0
                    && failureReason == PreAuthWebRtcFailureReason.IP_FAMILY_INCOMPLETE
                    && !webRtcIps.isEmpty();
            case NETWORK_CHANGED, OAUTH_ATTEMPT_REQUIRED,
                    STALE_REPORT, STATE_INVALID -> probeGeneration == 0
                    && pendingUntil == null && pendingRemainingMillis == 0
                    && failureReason == null && webRtcIps.isEmpty();
        };
        if (!valid) {
            throw new IllegalArgumentException("WebRTC decision state is inconsistent.");
        }
    }

    public Boolean webRtcStatus() {
        return switch (outcome) {
            case VERIFIED -> Boolean.TRUE;
            case VERIFICATION_PENDING, VERIFICATION_REQUIRED, NETWORK_CHANGED,
                    OAUTH_ATTEMPT_REQUIRED, STALE_REPORT, STATE_INVALID -> null;
            case VERIFICATION_FAILED, VERIFICATION_TIMEOUT,
                    IP_FAMILY_INCOMPLETE, IP_MISMATCH -> Boolean.FALSE;
        };
    }

    public String verificationState() {
        return switch (outcome) {
            case VERIFIED -> "VERIFIED";
            case VERIFICATION_PENDING -> "PENDING";
            case VERIFICATION_REQUIRED -> "REQUIRED";
            case VERIFICATION_FAILED, VERIFICATION_TIMEOUT,
                    IP_FAMILY_INCOMPLETE, IP_MISMATCH -> "FAILED";
            case NETWORK_CHANGED -> "NETWORK_CHANGED";
            case OAUTH_ATTEMPT_REQUIRED -> "OAUTH_ATTEMPT_REQUIRED";
            case STALE_REPORT -> "STALE";
            case STATE_INVALID -> "INVALID";
        };
    }

    public static WebRtcVerificationDecision required(
            long generation,
            Instant startDeadline) {
        return new WebRtcVerificationDecision(
                WebRtcVerificationOutcome.VERIFICATION_REQUIRED,
                generation,
                startDeadline,
                0L,
                null,
                List.of());
    }

    public static WebRtcVerificationDecision required() {
        return required(1L, Instant.MAX);
    }

    public static WebRtcVerificationDecision pending(
            long generation,
            Instant until,
            long remainingMillis) {
        return new WebRtcVerificationDecision(
                WebRtcVerificationOutcome.VERIFICATION_PENDING,
                generation,
                until,
                remainingMillis,
                null,
                List.of());
    }

    public static WebRtcVerificationDecision pending(long generation, Instant until) {
        return pending(generation, until, 0L);
    }

    public static WebRtcVerificationDecision verified(long generation, List<String> ips) {
        return new WebRtcVerificationDecision(
                WebRtcVerificationOutcome.VERIFIED,
                generation,
                null,
                0L,
                null,
                ips);
    }

    public static WebRtcVerificationDecision verified(List<String> ips) {
        return verified(1L, ips);
    }

    public static WebRtcVerificationDecision failed(
            long generation,
            PreAuthWebRtcFailureReason reason,
            List<String> ips) {
        WebRtcVerificationOutcome outcome = switch (reason) {
            case IP_MISMATCH -> WebRtcVerificationOutcome.IP_MISMATCH;
            case IP_FAMILY_INCOMPLETE ->
                    WebRtcVerificationOutcome.IP_FAMILY_INCOMPLETE;
            case START_TIMEOUT, REPORT_TIMEOUT ->
                    WebRtcVerificationOutcome.VERIFICATION_TIMEOUT;
            case NO_PUBLIC_CANDIDATE ->
                    WebRtcVerificationOutcome.VERIFICATION_FAILED;
        };
        return new WebRtcVerificationDecision(
                outcome,
                generation,
                null,
                0L,
                reason,
                ips);
    }

    public static WebRtcVerificationDecision failed(
            long generation,
            Instant ignoredDeadline,
            PreAuthWebRtcFailureReason reason,
            List<String> ips) {
        return failed(generation, reason, ips);
    }

    public static WebRtcVerificationDecision failed() {
        return failed(
                1L,
                PreAuthWebRtcFailureReason.NO_PUBLIC_CANDIDATE,
                List.of());
    }

    public static WebRtcVerificationDecision mismatch(List<String> ips) {
        return failed(1L, PreAuthWebRtcFailureReason.IP_MISMATCH, ips);
    }

    public static WebRtcVerificationDecision networkChanged() {
        return stateless(WebRtcVerificationOutcome.NETWORK_CHANGED);
    }

    public static WebRtcVerificationDecision stale() {
        return stateless(WebRtcVerificationOutcome.STALE_REPORT);
    }

    public static WebRtcVerificationDecision oauthAttemptRequired() {
        return stateless(WebRtcVerificationOutcome.OAUTH_ATTEMPT_REQUIRED);
    }

    public static WebRtcVerificationDecision stateInvalid() {
        return stateless(WebRtcVerificationOutcome.STATE_INVALID);
    }

    private static WebRtcVerificationDecision stateless(
            WebRtcVerificationOutcome outcome) {
        return new WebRtcVerificationDecision(
                outcome,
                0L,
                null,
                0L,
                null,
                List.of());
    }
}
