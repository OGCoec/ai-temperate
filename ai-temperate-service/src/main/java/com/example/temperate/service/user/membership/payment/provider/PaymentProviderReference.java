package com.example.temperate.service.user.membership.payment.provider;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import java.util.Objects;

/**
 * 该工具是来为 BAR 与六号的真实第三方交易号添加可验证路由前缀，并在查询、回调和关单前安全拆封。
 *
 * <p>外部支付引用只有 {@code PROVIDER:TRADE:平台流水号} 一种合法形式；本地订单号不能伪装成
 * 第三方交易号，缺失或无前缀引用也不能通过默认配置猜测外部 Provider。</p>
 */
public final class PaymentProviderReference {

    private static final String TRADE_KIND = ":TRADE:";

    private PaymentProviderReference() {
    }

    public static String trade(PaymentProviderType provider, String providerTradeNo) {
        PaymentProviderType value = requireExternalProvider(provider);
        if (providerTradeNo != null
                && (providerTradeNo.startsWith("BAR:TRADE:")
                        || providerTradeNo.startsWith("LIUHAO:TRADE:"))
                && resolveTrade(providerTradeNo) != value) {
            throw new IllegalArgumentException(
                    "Payment provider trade reference belongs to another provider.");
        }
        String raw = rawTradeNo(providerTradeNo);
        if (raw == null || raw.contains(":ORDER:")) {
            throw new IllegalArgumentException("Payment provider trade reference is invalid.");
        }
        return requireLength(value.name() + TRADE_KIND + requireText(raw));
    }

    public static PaymentProviderType resolveTrade(String providerTradeNo) {
        PaymentProviderType resolved = tryResolveTrade(providerTradeNo);
        if (resolved == null) {
            throw new IllegalArgumentException("External payment trade reference is missing.");
        }
        return resolved;
    }

    public static PaymentProviderType tryResolveTrade(String providerTradeNo) {
        if (providerTradeNo == null || providerTradeNo.isBlank()) {
            return null;
        }
        for (PaymentProviderType provider : new PaymentProviderType[] {
                PaymentProviderType.BAR, PaymentProviderType.LIUHAO}) {
            String prefix = provider.name() + TRADE_KIND;
            if (providerTradeNo.startsWith(prefix)) {
                requireText(providerTradeNo.substring(prefix.length()));
                return provider;
            }
        }
        throw new IllegalArgumentException("External payment trade reference is invalid.");
    }

    public static String rawTradeNo(String providerTradeNo) {
        if (providerTradeNo == null || providerTradeNo.isBlank()) {
            return null;
        }
        if (providerTradeNo.contains(":ORDER:")) {
            throw new IllegalArgumentException(
                    "Local order placeholders are not provider trade references.");
        }
        for (PaymentProviderType provider : new PaymentProviderType[] {
                PaymentProviderType.BAR, PaymentProviderType.LIUHAO}) {
            String tradePrefix = provider.name() + TRADE_KIND;
            if (providerTradeNo.startsWith(tradePrefix)) {
                return requireText(providerTradeNo.substring(tradePrefix.length()));
            }
        }
        return requireText(providerTradeNo);
    }

    public static boolean isTrade(
            PaymentProviderType provider,
            String providerTradeNo) {
        return providerTradeNo != null
                && providerTradeNo.startsWith(
                        requireExternalProvider(provider).name() + TRADE_KIND)
                && rawTradeNo(providerTradeNo) != null;
    }

    private static PaymentProviderType requireExternalProvider(PaymentProviderType provider) {
        PaymentProviderType value = Objects.requireNonNull(provider);
        if (value != PaymentProviderType.BAR && value != PaymentProviderType.LIUHAO) {
            throw new IllegalArgumentException("Only external payment providers use tagged references.");
        }
        return value;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Payment provider reference is invalid.");
        }
        return value;
    }

    private static String requireLength(String value) {
        if (value.length() > 128) {
            throw new IllegalArgumentException("Payment provider reference exceeds 128 characters.");
        }
        return value;
    }
}
