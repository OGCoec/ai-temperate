package com.example.temperate.service.auth.oauth.identity;

import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;

/**
 * 定义可信第三方身份在不写数据库的前提下解析本地账号和手机号前置要求的能力。
 */
public interface OAuthAccountResolutionService {

    OAuthAccountDecision resolve(TrustedOAuthIdentity identity);
}
