package com.example.temperate.service.auth.oauth.domain;

import java.util.Objects;

/**
 * 表示 Provider 签名、回调绑定和重放校验全部通过后的统一可信第三方身份。
 *
 * <p>该值对象只允许携带稳定 Subject 和已验证邮箱，不包含 Provider Access Token、ID Token、授权码
 * 或头像资料，防止临时凭据跨越账号解析边界。</p>
 */
public record TrustedOAuthIdentity(
        OAuthProvider provider,
        String providerSubject,
        String verifiedEmail,
        boolean emailVerified,
        OAuthProofType proofType) {

    public TrustedOAuthIdentity {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(proofType, "proofType must not be null");
        if (!validSubject(providerSubject)) {
            throw new IllegalArgumentException("Provider subject is invalid.");
        }
        if (verifiedEmail == null || verifiedEmail.isBlank() || verifiedEmail.length() > 254) {
            throw new IllegalArgumentException("Verified email is invalid.");
        }
        if (!emailVerified) {
            throw new IllegalArgumentException("Provider email must be verified.");
        }
    }

    private static boolean validSubject(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            return false;
        }
        return subject.codePoints().noneMatch(codePoint ->
                Character.isISOControl(codePoint) || Character.isWhitespace(codePoint));
    }
}
