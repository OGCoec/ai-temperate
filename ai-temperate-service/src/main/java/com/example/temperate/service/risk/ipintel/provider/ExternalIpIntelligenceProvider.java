package com.example.temperate.service.risk.ipintel.provider;

import com.example.temperate.service.risk.ipintel.domain.ExternalIpProviderType;
import com.example.temperate.service.risk.ipintel.domain.ProviderIpIntelligenceResult;
import reactor.core.publisher.Mono;

/**
 * 定义外部 IP 情报查询策略；实现必须异步返回并自行把供应商字段映射为内部统一语义。
 */
public interface ExternalIpIntelligenceProvider {

    ExternalIpProviderType type();

    Mono<ProviderIpIntelligenceResult> query(String canonicalIp);
}
