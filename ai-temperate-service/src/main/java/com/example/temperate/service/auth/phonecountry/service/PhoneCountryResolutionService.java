package com.example.temperate.service.auth.phonecountry.service;

import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * 定义认证层对客户端 IP 国家代码的异步安全解析边界。
 *
 * <p>用途：隔离同步国家库查询并向 Web 层提供冷 {@link Mono}；无结果使用空值表达，超过服务端期限时由实现抛出受控异常。</p>
 */
public interface PhoneCountryResolutionService {

    /**
     * 根据规范客户端 IP 异步解析 ISO2 国家代码。
     *
     * <p>返回的发布者必须保持惰性，调用该方法本身不得触发本地文件读取。</p>
     */
    Mono<Optional<String>> resolveCountryIso2(String canonicalClientIp);
}
