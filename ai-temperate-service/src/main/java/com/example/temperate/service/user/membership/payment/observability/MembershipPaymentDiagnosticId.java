package com.example.temperate.service.user.membership.payment.observability;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * 该工具是来把高熵会员订单公共ID转换为不可逆日志摘要，使HTTP、Rabbit和回调链可以关联而不输出原始资源标识。
 */
public final class MembershipPaymentDiagnosticId {

    private static final HybridBase64UrlCodec ID_CODEC = new HybridBase64UrlCodec();

    private MembershipPaymentDiagnosticId() {
    }

    public static String orderRef(String publicOrderId) {
        return orderRef(ID_CODEC.decode(Objects.requireNonNull(publicOrderId)));
    }

    public static String orderRef(byte[] internalOrderId) {
        byte[] value = Objects.requireNonNull(internalOrderId).clone();
        if (value.length != HybridBase64UrlCodec.BINARY_LENGTH) {
            throw new IllegalArgumentException("Membership payment order ID length is invalid.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
