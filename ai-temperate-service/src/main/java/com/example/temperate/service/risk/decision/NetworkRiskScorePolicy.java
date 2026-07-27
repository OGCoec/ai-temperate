package com.example.temperate.service.risk.decision;

import com.example.temperate.service.risk.domain.RiskDecision;

/**
 * 集中定义基础信用分与最终分的固定决策边界，确保首次 Bootstrap 和后续请求使用同一规则。
 */
public final class NetworkRiskScorePolicy {

    private NetworkRiskScorePolicy() {
    }

    public static RiskDecision decide(int baseScore, int finalScore) {
        if (baseScore < 40 || finalScore < 20) {
            return RiskDecision.BLOCK;
        }
        if (finalScore < 60) {
            return RiskDecision.CHALLENGE;
        }
        return RiskDecision.ALLOW;
    }
}
