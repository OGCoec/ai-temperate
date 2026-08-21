package com.example.temperate.web.user.membership.payment;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 该响应是来向认证客户端公开会员订单的规范 ID、精确字符串金额和当前状态时间边界，不泄露内部数据库主键。
 */
public record MembershipOrderResponse(
        @Schema(
                minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                pattern = HybridBase64UrlCodec.ENCODED_PATTERN,
                example = "AaAjECcaAQGqi_h2Rl1PiA")
        String orderId,
        MembershipTier membershipTier,
        @Schema(example = "20.00") String payAmountYuan,
        String payType,
        MembershipOrderStatus status,
        OffsetDateTime paymentStartedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime closingDeadlineAt,
        OffsetDateTime paidAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static MembershipOrderResponse from(MembershipOrderSnapshot snapshot) {
        MembershipOrderSnapshot value = Objects.requireNonNull(snapshot);
        return new MembershipOrderResponse(
                value.orderId(),
                value.membershipTier(),
                value.payAmountYuan().toPlainString(),
                value.payType(),
                value.status(),
                value.paymentStartedAt(),
                value.expiresAt(),
                value.closingDeadlineAt(),
                value.paidAt(),
                value.createdAt(),
                value.updatedAt());
    }
}
