package com.example.temperate.web.user.membership.payment.id;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.Objects;

/**
 * 该值对象是来同时承载已经规范校验的会员订单 Base64URL 和 16 字节内部主键，供路径转换后执行资源级授权。
 */
public record MembershipOrderPublicId(String encoded, byte[] internalValue) {

    public MembershipOrderPublicId {
        Objects.requireNonNull(encoded, "encoded must not be null");
        internalValue = Objects.requireNonNull(
                        internalValue, "internalValue must not be null")
                .clone();
        int aggregate = 0;
        for (byte current : internalValue) {
            aggregate |= current;
        }
        if (internalValue.length != HybridBase64UrlCodec.BINARY_LENGTH || aggregate == 0) {
            throw new IllegalArgumentException(
                    "Membership order internal ID must contain 16 non-zero bytes.");
        }
    }

    @Override
    public byte[] internalValue() {
        return internalValue.clone();
    }
}
