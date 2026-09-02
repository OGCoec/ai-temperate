package com.example.temperate.service.user.membership.payment.provider.liuhao;

/**
 * 该枚举是来标识六号响应验签失败的固定层级，供受控日志定位问题且不携带任何响应原值或密钥材料。
 */
public enum LiuhaoSignatureVerificationReason {
    VERIFIED,
    SIGN_TYPE_MISSING,
    SIGN_TYPE_UNEXPECTED,
    SIGN_MISSING,
    SIGN_BASE64_INVALID,
    CANONICAL_FIELDS_UNEXPECTED,
    PLATFORM_SIGNATURE_MISMATCH,
    CRYPTO_VERIFIER_UNAVAILABLE
}
