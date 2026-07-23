package com.example.temperate.service.auth.phonecountry.provider;

import java.util.Optional;

/**
 * 定义从规范客户端 IP 推断 ISO 3166-1 alpha-2 国家代码的查询能力。
 *
 * <p>无法解析时返回空值，调用方必须采用显式降级策略，不能把空值视为可信国家信息。</p>
 */
@FunctionalInterface
public interface IpCountryProvider {

    Optional<String> findCountryIso2(String canonicalClientIp);
}
