package com.example.temperate.service.user.membership.payment.provider.liuhao;

import java.util.Objects;

/**
 * 该记录是来返回六号响应的不可变验签结论，只暴露低基数原因而不保存签名、规范串或响应正文。
 */
public record LiuhaoSignatureVerificationResult(
        LiuhaoSignatureVerificationReason reason) {

    public LiuhaoSignatureVerificationResult {
        Objects.requireNonNull(reason);
    }

    public boolean verified() {
        return reason == LiuhaoSignatureVerificationReason.VERIFIED;
    }

    public static LiuhaoSignatureVerificationResult success() {
        return new LiuhaoSignatureVerificationResult(
                LiuhaoSignatureVerificationReason.VERIFIED);
    }

    public static LiuhaoSignatureVerificationResult failed(
            LiuhaoSignatureVerificationReason reason) {
        LiuhaoSignatureVerificationReason failure = Objects.requireNonNull(reason);
        if (failure == LiuhaoSignatureVerificationReason.VERIFIED) {
            throw new IllegalArgumentException(
                    "A failed Liuhao verification cannot be VERIFIED.");
        }
        return new LiuhaoSignatureVerificationResult(failure);
    }
}
