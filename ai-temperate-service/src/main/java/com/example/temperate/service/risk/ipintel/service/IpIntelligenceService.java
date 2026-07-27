package com.example.temperate.service.risk.ipintel.service;

import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceLookupResult;
import reactor.core.publisher.Mono;

/**
 * 定义按规范化客户端 IP 查询统一信用、网络类型和地理信息的异步业务接口。
 */
public interface IpIntelligenceService {

    Mono<IpIntelligenceLookupResult> lookup(String canonicalClientIp);
}
