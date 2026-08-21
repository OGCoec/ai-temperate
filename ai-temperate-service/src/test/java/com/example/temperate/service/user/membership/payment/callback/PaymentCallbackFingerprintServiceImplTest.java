package com.example.temperate.service.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.membership.payment.callback.impl.PaymentCallbackFingerprintServiceImpl;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 该指纹测试是来锁定 GET/POST 共用的五行 HMAC 原文，并确认摘要排除 sign、buyer 但仍覆盖金额等业务字段。
 */
final class PaymentCallbackFingerprintServiceImplTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Test
    void fingerprintUsesTheExactFiveLineCanonicalText() throws Exception {
        PaymentCallbackFingerprintService service =
                new PaymentCallbackFingerprintServiceImpl(properties());
        SimulatedLiuhaoCallbackCommand command = command("20.00", "buyer-a", "sign-a");
        String canonical = String.join(
                "\n",
                "SIMULATED",
                command.pid(),
                command.tradeNo(),
                command.outTradeNo(),
                command.tradeStatus());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(service.fingerprint(command).value()).isEqualTo(expected);
    }

    @Test
    void providerTradeFingerprintIsStableAcrossOrderIdsButChangesWithTradeNumber() {
        PaymentCallbackFingerprintService service =
                new PaymentCallbackFingerprintServiceImpl(properties());
        SimulatedLiuhaoCallbackCommand first = command("20.00", "buyer-a", "sign-a");
        SimulatedLiuhaoCallbackCommand anotherOrder = new SimulatedLiuhaoCallbackCommand(
                first.pid(), first.tradeNo(), "AQEBAQEBAQEBAQEBAQEBAQ", first.apiTradeNo(),
                first.type(), first.tradeStatus(), first.addTime(), first.endTime(),
                first.name(), first.money(), first.param(), first.buyer(), first.timestamp(),
                first.sign(), first.signType());
        SimulatedLiuhaoCallbackCommand anotherTrade = new SimulatedLiuhaoCallbackCommand(
                first.pid(), "provider-trade-2", first.outTradeNo(), first.apiTradeNo(),
                first.type(), first.tradeStatus(), first.addTime(), first.endTime(),
                first.name(), first.money(), first.param(), first.buyer(), first.timestamp(),
                first.sign(), first.signType());

        assertThat(service.providerTradeFingerprint(anotherOrder).value())
                .isEqualTo(service.providerTradeFingerprint(first).value());
        assertThat(service.providerTradeFingerprint(anotherTrade).value())
                .isNotEqualTo(service.providerTradeFingerprint(first).value());
    }

    @Test
    void payloadDigestExcludesSignatureAndBuyerButCoversMoney() {
        PaymentCallbackFingerprintService service =
                new PaymentCallbackFingerprintServiceImpl(properties());

        String original = service.payloadDigest(command("20.00", "buyer-a", "sign-a"));
        String excludedChanged =
                service.payloadDigest(command("20.00", "buyer-b", "sign-b"));
        String moneyChanged =
                service.payloadDigest(command("20.01", "buyer-a", "sign-a"));

        assertThat(excludedChanged).isEqualTo(original);
        assertThat(moneyChanged).isNotEqualTo(original);
    }

    private static SimulatedLiuhaoCallbackCommand command(
            String money,
            String buyer,
            String sign) {
        return new SimulatedLiuhaoCallbackCommand(
                "merchant-test",
                "provider-trade-1",
                "AaAjECcaAQGqi_h2Rl1PiA",
                "channel-trade-1",
                "alipay",
                "TRADE_SUCCESS",
                "2026-08-20 11:59:50",
                "2026-08-20 11:59:55",
                "PLUS membership",
                money,
                "",
                buyer,
                "1787227200",
                sign,
                "RSA");
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        true,
                        "merchant-test",
                        KEY,
                        Duration.ofMinutes(5),
                        16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(10),
                        Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(
                                10_000L,
                                10_000L,
                                10_000L,
                                15_000L,
                                15_000L,
                                30_000L,
                                30_000L,
                                60_000L,
                                120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30),
                        3));
    }
}
