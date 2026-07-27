package com.example.temperate.service.risk.decision;

import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import reactor.core.publisher.Mono;

/**
 * 定义同 IP 快速放行与 IP 变化实时评分的网络风险业务边界。
 */
public interface NetworkRiskAssessmentService {

    Mono<RiskAssessment> assess(
            PreAuthAccess access,
            TrustedNetworkObservation currentObservation);
}
