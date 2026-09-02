package com.example.temperate.service.auth.oauth.webrtc.store;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.webrtc.OAuthWebRtcAttemptService.State;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import java.time.Duration;
import java.time.Instant;

/**
 * 定义 OAuth WebRTC attempt、PreAuth 与 Refresh Session 跨键状态转换的 Redis 原子边界。
 */
public interface OAuthWebRtcAttemptStore {

    /** 原子绑定既有 PENDING generation 与 OAuth Flow。 */
    SuspendStoreResult suspend(SuspendStoreCommand command);

    /** 幂等恢复原 attempt，并在绑定变化时最多执行一次替代。 */
    ResumeStoreResult resume(ResumeStoreCommand command);

    /** 判断 pending OAuth complete 是否仍处于同一安全上下文。 */
    boolean canComplete(AttemptLookup lookup);

    /** 原子轮换 PreAuth 并标记刚创建的 Refresh Session 为 PENDING。 */
    PendingStoreResult issuePendingSession(PendingSessionCommand command);

    /** 原子激活或撤销与 report 绑定的 Refresh Session。 */
    PreAuthWebRtcWriteResult decideReport(ReportDecisionCommand command);

    /** 只读检查 attempt 终态和原始截止时间。 */
    VerdictStoreResult inspect(AttemptLookup lookup);

    /** 绑定 OAuth Flow 与现有 PENDING generation 的存储命令。 */
    record SuspendStoreCommand(
            RiskScope scope,
            HmacIdentifier preAuthTokenDigest,
            HmacIdentifier attemptDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            HmacIdentifier oauthFlowDigest,
            long generation,
            HmacIdentifier probeRunDigest,
            Instant suspendExpiresAt) {
    }

    /** 恢复原 attempt，或在网络变化时只允许一次替代 generation 的存储命令。 */
    record ResumeStoreCommand(
            RiskScope scope,
            HmacIdentifier preAuthTokenDigest,
            HmacIdentifier attemptDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            HmacIdentifier oauthFlowDigest,
            long generation,
            Duration verificationWindow) {
    }

    /** 查询或完成请求使用的最小绑定材料。 */
    record AttemptLookup(
            RiskScope scope,
            HmacIdentifier preAuthTokenDigest,
            HmacIdentifier attemptDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            long generation) {
    }

    /** 把已创建但尚未交付的 Refresh Session 原子标记为 PENDING 并轮换 PreAuth。 */
    record PendingSessionCommand(
            RiskScope scope,
            HmacIdentifier oldPreAuthTokenDigest,
            HmacIdentifier newPreAuthTokenDigest,
            HmacIdentifier attemptDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            HmacIdentifier oauthFlowDigest,
            long generation,
            HmacIdentifier sessionReferenceDigest,
            HmacIdentifier refreshTokenDigest,
            HmacIdentifier refreshDeviceDigest,
            HmacIdentifier decisionContextDigest,
            RiskSessionType sessionType,
            Instant seenAt,
            Duration verdictWindow,
            Duration authenticatedPreAuthTtl) {
    }

    /** 对原 generation 的 report 同时更新 PreAuth、attempt 和 Refresh Session。 */
    record ReportDecisionCommand(
            RiskScope scope,
            HmacIdentifier preAuthTokenDigest,
            HmacIdentifier attemptDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            long generation,
            boolean verified,
            PreAuthWebRtcFailureReason failureReason,
            String encryptedWebRtcIps,
            boolean hasReportedIps,
            Duration authenticatedPreAuthTtl) {
    }

    /** suspend Lua 的稳定返回。 */
    record SuspendStoreResult(State state, long generation) {
    }

    /** resume Lua 的稳定返回。 */
    record ResumeStoreResult(State state, long generation, boolean fallbackUsed) {
    }

    /** pending session Lua 的稳定返回。 */
    record PendingStoreResult(boolean issued, Instant verdictDeadlineAt) {
    }

    /** 只读 verdict 查询的稳定返回。 */
    record VerdictStoreResult(State state, long generation, Instant verdictDeadlineAt) {
    }
}
