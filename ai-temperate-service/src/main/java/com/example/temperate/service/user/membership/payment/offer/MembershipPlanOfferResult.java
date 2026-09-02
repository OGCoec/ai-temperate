package com.example.temperate.service.user.membership.payment.offer;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 该结果是来一次返回当前有效套餐、支付环境和全部允许购买的个人套餐报价。
 */
public record MembershipPlanOfferResult(
        MembershipTier currentTier,
        PaymentProviderType provider,
        boolean checkoutEnabled,
        OffsetDateTime quotedAt,
        List<String> payTypes,
        List<MembershipPlanOffer> offers,
        List<PaymentOption> paymentOptions) {

    public MembershipPlanOfferResult {
        Objects.requireNonNull(currentTier, "Current membership tier is required.");
        Objects.requireNonNull(provider, "Payment provider is required.");
        Objects.requireNonNull(quotedAt, "Quote time is required.");
        payTypes = List.copyOf(Objects.requireNonNull(payTypes, "Payment types are required."));
        offers = List.copyOf(Objects.requireNonNull(offers, "Membership offers are required."));
        paymentOptions = List.copyOf(Objects.requireNonNull(
                paymentOptions, "Payment options are required."));
    }

    /** 兼容旧调用方的构造器仍保留单 Provider 视图，新接口通过 paymentOptions 暴露公开 Provider 白名单。 */
    public MembershipPlanOfferResult(
            MembershipTier currentTier,
            PaymentProviderType provider,
            boolean checkoutEnabled,
            OffsetDateTime quotedAt,
            List<String> payTypes,
            List<MembershipPlanOffer> offers) {
        this(
                currentTier,
                provider,
                checkoutEnabled,
                quotedAt,
                payTypes,
                offers,
                List.of(new PaymentOption(provider, payTypes, checkoutMode(provider))));
    }

    /** 该值对象是来描述前端公开的 Provider、支付方式和浏览器跳转模式，不包含任何密钥。 */
    public record PaymentOption(
            PaymentProviderType provider,
            List<String> payTypes,
            PaymentCheckoutMode checkoutMode) {

        public PaymentOption {
            Objects.requireNonNull(provider, "Payment provider is required.");
            payTypes = List.copyOf(Objects.requireNonNull(payTypes, "Payment types are required."));
            Objects.requireNonNull(checkoutMode, "Checkout mode is required.");
        }
    }

    private static PaymentCheckoutMode checkoutMode(PaymentProviderType provider) {
        Objects.requireNonNull(provider, "Payment provider is required.");
        return provider == PaymentProviderType.LIUHAO
                ? PaymentCheckoutMode.REDIRECT_URL
                : PaymentCheckoutMode.FORM_POST;
    }
}
