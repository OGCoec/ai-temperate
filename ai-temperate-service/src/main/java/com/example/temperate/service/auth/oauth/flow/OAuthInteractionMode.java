package com.example.temperate.service.auth.oauth.flow;

/**
 * 表示 OAuth Provider 证明通过浏览器授权码还是 Android 原生 Google ID Token 完成。
 */
public enum OAuthInteractionMode {
    BROWSER,
    GOOGLE_NATIVE
}
