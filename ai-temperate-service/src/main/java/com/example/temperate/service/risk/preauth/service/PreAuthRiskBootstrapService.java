package com.example.temperate.service.risk.preauth.service;

import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.preauth.domain.PreAuthBootstrapOutcome;
import reactor.core.publisher.Mono;

/**
 * 定义首次或迁移后的 PreAuth IP 信用评估、状态创建与 Challenge 编排业务。
 */
public interface PreAuthRiskBootstrapService {

    Mono<PreAuthBootstrapOutcome> bootstrap(
            RiskScope scope,
            String existingRawToken,
            String rawDeviceId,
            TrustedNetworkObservation observation,
            boolean resetExisting);
}
