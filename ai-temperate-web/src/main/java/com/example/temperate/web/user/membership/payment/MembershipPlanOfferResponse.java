package com.example.temperate.web.user.membership.payment;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.offer.MembershipPlanOffer;
import com.example.temperate.service.user.membership.payment.offer.MembershipPlanOfferResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutMode;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 该响应是来向已认证 H5 返回当前有效套餐、支付开关和可直接展示的服务端报价卡片。
 */
public record MembershipPlanOfferResponse(
        MembershipTier currentTier,
        PaymentProviderType provider,
        boolean checkoutEnabled,
        OffsetDateTime quotedAt,
        List<String> payTypes,
        List<Offer> offers,
        List<PaymentOption> paymentOptions) {

    public static MembershipPlanOfferResponse from(MembershipPlanOfferResult result) {
        MembershipPlanOfferResult value = Objects.requireNonNull(result);
        return new MembershipPlanOfferResponse(
                value.currentTier(),
                value.provider(),
                value.checkoutEnabled(),
                value.quotedAt(),
                value.payTypes(),
                value.offers().stream().map(Offer::from).toList(),
                value.paymentOptions().stream().map(PaymentOption::from).toList());
    }

    /** 兼容旧响应构造器，新增 paymentOptions 时不改变已有测试和内部调用。 */
    public MembershipPlanOfferResponse(
            MembershipTier currentTier,
            PaymentProviderType provider,
            boolean checkoutEnabled,
            OffsetDateTime quotedAt,
            List<String> payTypes,
            List<Offer> offers) {
        this(currentTier, provider, checkoutEnabled, quotedAt, payTypes, offers,
                List.of(new PaymentOption(
                        provider,
                        payTypes,
                        PaymentCheckoutMode.FORM_POST)));
    }

    public record PaymentOption(
            PaymentProviderType provider,
            List<String> payTypes,
            PaymentCheckoutMode checkoutMode) {

        private static PaymentOption from(
                MembershipPlanOfferResult.PaymentOption option) {
            return new PaymentOption(option.provider(), option.payTypes(), option.checkoutMode());
        }
    }

    /** 该嵌套响应把 BigDecimal 直接序列化为两位小数字符串，避免浏览器浮点重算价格。 */
    public record Offer(
            MembershipTier targetTier,
            String displayName,
            String listPriceYuan,
            String creditAmountYuan,
            String payAmountYuan,
            MembershipTransitionType transitionType) {

        private static Offer from(MembershipPlanOffer offer) {
            return new Offer(
                    offer.targetTier(),
                    offer.displayName(),
                    offer.listPriceYuan().toPlainString(),
                    offer.creditAmountYuan().toPlainString(),
                    offer.payAmountYuan().toPlainString(),
                    offer.transitionType());
        }
    }
}
