package com.example.temperate.service.auth.login.dto.internal;

import com.example.temperate.service.auth.login.enums.LoginIdentifierType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 表示登录输入经过格式校验和规范化后的内部认证数据。
 *
 * <p>该对象供身份查询、密码校验和风控使用，仍含短生命周期的明文密码，不能跨出登录服务边界。</p>
 */
@Getter
@RequiredArgsConstructor
public final class NormalizedLoginInput {

    private final LoginIdentifierType identifierType;
    private final String identifier;
    private final String rawPassword;
    private final String deviceInstallationId;
    private final String canonicalClientIp;
}
