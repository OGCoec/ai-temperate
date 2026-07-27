package com.example.temperate.service.risk.preauth.domain;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示一次已通过作用域、Token 和设备绑定校验的 PreAuth 访问上下文。
 */
public record PreAuthAccess(
        HmacIdentifier tokenDigest,
        PreAuthState state) {
}
