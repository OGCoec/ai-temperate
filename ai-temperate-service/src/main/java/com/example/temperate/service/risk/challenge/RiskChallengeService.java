package com.example.temperate.service.risk.challenge;

import com.example.temperate.service.risk.decision.RiskAssessment;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;

/**
 * 定义 WAF Challenge 引用签发和验证成功后可信 IP 原子推进业务。
 */
public interface RiskChallengeService {

    RiskChallengeIssue issue(PreAuthAccess access, RiskAssessment assessment);

    boolean consumeAndTrust(
            PreAuthAccess access,
            String rawReference,
            TrustedNetworkObservation currentObservation);
}
