package com.example.temperate.service.auth.oauth.domain;

/**
 * 表示可信第三方身份在进入统一账号解析前已经完成的证明方式。
 */
public enum OAuthProofType {
    BROWSER_AUTHORIZATION_CODE,
    GOOGLE_NATIVE_ID_TOKEN
}
