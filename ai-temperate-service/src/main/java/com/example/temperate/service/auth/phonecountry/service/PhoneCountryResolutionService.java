package com.example.temperate.service.auth.phonecountry.service;

import java.util.Optional;

/**
 * 定义认证层对客户端 IP 国家代码的安全解析边界。
 */
public interface PhoneCountryResolutionService {

    Optional<String> resolveCountryIso2(String canonicalClientIp);
}
