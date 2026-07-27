package com.example.temperate.service.risk.preauth.domain;

import com.example.temperate.service.risk.challenge.RiskChallengeIssue;
import com.example.temperate.service.risk.decision.RiskAssessment;

/**
 * 承载一次 PreAuth Bootstrap 的内部结果，统一关联新凭证、风险决策、Challenge 和迁移状态。
 */
public record PreAuthBootstrapOutcome(
        PreAuthIssue issue,
        PreAuthAccess access,
        RiskAssessment assessment,
        RiskChallengeIssue challenge,
        boolean reauthenticationRequired) {

    public PreAuthBootstrapOutcome {
        if (issue == null || access == null || assessment == null) {
            throw new IllegalArgumentException("PreAuth bootstrap outcome is incomplete.");
        }
    }
}
