package com.example.temperate.service.risk.decision;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.domain.RiskDecision;

/**
 * 保存一次实时网络风险决策的内部结果；该对象禁止直接序列化给客户端。
 */
public record RiskAssessment(
        RiskDecision decision,
        int finalScore,
        boolean impossibleTravel,
        long recentImpossibleTravelCount,
        HmacIdentifier currentIpDigest,
        HmacIdentifier decisionContextDigest) {

    public static RiskAssessment allowFast(
            HmacIdentifier currentIpDigest,
            HmacIdentifier decisionContextDigest) {
        return new RiskAssessment(
                RiskDecision.ALLOW,
                100,
                false,
                0,
                currentIpDigest,
                decisionContextDigest);
    }
}
