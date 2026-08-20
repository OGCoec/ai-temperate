package com.example.temperate.web.auth.oauth.nativegoogle;

/**
 * 定义 Android Credential Manager Google ID Token 的服务端签名与声明验证能力。
 */
public interface GoogleNativeIdentityVerifier {

    VerifiedGoogleNativeIdentity verify(String rawIdToken);
}
