package com.example.temperate.service.risk.ipintel.local;

import java.util.Optional;

/**
 * 定义外部 IP 情报失败时从本地 BIN 补充国家、ASN 与坐标的只读边界。
 */
public interface LocalIpGeoProvider {

    Optional<LocalIpGeoResult> findGeo(String canonicalClientIp);
}
