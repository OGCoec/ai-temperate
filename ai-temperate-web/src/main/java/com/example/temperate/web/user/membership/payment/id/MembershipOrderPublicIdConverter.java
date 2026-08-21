package com.example.temperate.web.user.membership.payment.id;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 该转换器是来在会员订单 Controller 执行前统一校验 22 字符规范 Base64URL，并解码成不可变的内部资源 ID。
 */
@Component
public final class MembershipOrderPublicIdConverter
        implements Converter<String, MembershipOrderPublicId> {

    private final HybridBase64UrlCodec base64UrlCodec;

    public MembershipOrderPublicIdConverter(HybridBase64UrlCodec base64UrlCodec) {
        this.base64UrlCodec = Objects.requireNonNull(base64UrlCodec);
    }

    @Override
    public MembershipOrderPublicId convert(String source) {
        byte[] internalId = base64UrlCodec.decode(source);
        if (!base64UrlCodec.encode(internalId).equals(source)) {
            throw new IllegalArgumentException(
                    "Membership order public ID is not canonical.");
        }
        return new MembershipOrderPublicId(source, internalId);
    }
}
