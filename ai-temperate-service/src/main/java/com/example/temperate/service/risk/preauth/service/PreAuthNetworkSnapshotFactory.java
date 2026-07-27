package com.example.temperate.service.risk.preauth.service;

import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;

/**
 * 定义 Cloudflare 可信边缘地理信息与共享 IP 情报快照的合并边界。
 */
public interface PreAuthNetworkSnapshotFactory {

    PreAuthNetworkSnapshot merge(
            TrustedNetworkObservation observation,
            IpIntelligenceSnapshot intelligence);
}
