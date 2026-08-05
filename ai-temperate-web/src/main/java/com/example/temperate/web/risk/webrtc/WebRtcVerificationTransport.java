package com.example.temperate.web.risk.webrtc;

import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/**
 * 通过两个低敏响应头向普通端和管理员端传输 WebRTC 后台任务触发信号。
 *
 * <p>响应头只包含低基数阶段和单调 generation，禁止携带 Token、设备摘要、HTTP IP 或候选集合。</p>
 */
@Component
public final class WebRtcVerificationTransport {

    public static final String STATE_HEADER = "X-AIT-WebRTC-State";
    public static final String GENERATION_HEADER = "X-AIT-WebRTC-Generation";

    public void write(HttpServletResponse response, PreAuthIssue issue) {
        write(response, issue.webRtcPhase(), issue.webRtcGeneration());
    }

    public void write(
            HttpServletResponse response,
            WebRtcVerificationDecision decision) {
        if (decision.probeGeneration() <= 0) {
            return;
        }
        response.setHeader(STATE_HEADER, decision.verificationState());
        response.setHeader(
                GENERATION_HEADER,
                Long.toString(decision.probeGeneration()));
    }

    public void write(
            HttpServletResponse response,
            PreAuthWebRtcPhase phase,
            long generation) {
        if (phase == null || generation <= 0) {
            return;
        }
        response.setHeader(STATE_HEADER, phase.name());
        response.setHeader(GENERATION_HEADER, Long.toString(generation));
    }
}
