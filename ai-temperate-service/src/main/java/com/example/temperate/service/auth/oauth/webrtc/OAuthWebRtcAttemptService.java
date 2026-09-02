package com.example.temperate.service.auth.oauth.webrtc;

import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import java.time.Instant;

/**
 * 管理 H5 OAuth 跨文档期间可恢复的单个 WebRTC attempt，并禁止回调页隐式创建第二个 PreAuth 或 generation。
 */
public interface OAuthWebRtcAttemptService {

    /** 把当前 PreAuth 的既有 PENDING generation 绑定到新 OAuth Flow，不创建替代任务。 */
    SuspendResult suspend(
            ProtectedOAuthFlowAccess flow,
            PreAuthAccess preAuth,
            String probeGeneration,
            String probeRunId,
            Instant suspendExpiresAt);

    /** 在回调页幂等恢复原 attempt；网络变化时仅允许存储层执行一次替代 generation。 */
    ResumeResult resume(
            ProtectedOAuthFlowAccess flow,
            PreAuthAccess preAuth,
            String rawAttemptId,
            String probeGeneration);

    /** 在 OAuth complete 前确认请求携带的是当前 PreAuth 下已恢复且未过期的 attempt。 */
    boolean isPendingH5OAuthCompletionAllowed(
            PreAuthAccess preAuth,
            String rawAttemptId,
            String probeGeneration);

    /** 只读查询 report 最终裁决，不推进状态或延长任何截止时间。 */
    VerdictStatus verdictStatus(
            PreAuthAccess preAuth,
            String rawAttemptId,
            String probeGeneration);

    /** 表示 OAuth 启动时对既有 WebRTC generation 的绑定结果。 */
    record SuspendResult(
            State state,
            String attemptId,
            String probeGeneration,
            boolean fallbackUsed) {
    }

    /** 表示 OAuth 回调对原 attempt 的幂等恢复结果。 */
    record ResumeResult(
            State state,
            String attemptId,
            String probeGeneration,
            boolean fallbackUsed) {
    }

    /** 表示 report 响应丢失后可只读查询的服务端最终状态。 */
    record VerdictStatus(
            State state,
            String probeGeneration,
            Instant verdictDeadlineAt) {
    }

    /** OAuth WebRTC attempt 的固定状态集合。 */
    enum State {
        OAUTH_SUSPENDED,
        RESUMED,
        VERIFIED,
        FAILED,
        EXPIRED,
        REPLACED
    }
}
