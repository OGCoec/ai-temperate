package com.example.temperate.service.auth.oauth.webrtc;

import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import java.time.Instant;

/**
 * 在 OAuth 登录完成与 WebRTC report 两个边界原子签发、激活或撤销待裁决刷新会话。
 */
public interface OAuthWebRtcSessionVerdictService {

    /** 原子轮换 PreAuth，并把尚未交付的 Refresh Session 标记为固定窗口内的 PENDING。 */
    PendingSession issuePendingOAuthVerdict(
            ProtectedOAuthFlowAccess flow,
            PreAuthAccess preAuth,
            String rawAttemptId,
            String probeGeneration,
            String rawRefreshToken,
            String rawDeviceId,
            Instant seenAt);

    /** 将同一 attempt 的 report 原子传播到 PreAuth、Refresh Session 与 attempt 三个状态。 */
    PreAuthWebRtcWriteResult decideReport(
            PreAuthAccess preAuth,
            String rawAttemptId,
            long probeGeneration,
            boolean verified,
            PreAuthWebRtcFailureReason failureReason,
            String encryptedWebRtcIps,
            boolean hasReportedIps);

    /** 表示认证 PreAuth 与 Refresh Session 已共同进入十五秒待裁决窗口。 */
    record PendingSession(
            PreAuthIssue preAuth,
            Instant verdictDeadlineAt) {
    }
}
