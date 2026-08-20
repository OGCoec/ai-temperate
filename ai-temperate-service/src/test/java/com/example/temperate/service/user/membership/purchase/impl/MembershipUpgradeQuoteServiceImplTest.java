package com.example.temperate.service.user.membership.purchase.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuoteCommand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定合法个人套餐升级按 UTC 剩余自然日抵扣、金额精度和异常输入边界。
 */
final class MembershipUpgradeQuoteServiceImplTest {

    @Test
    void proToMaxShowsOneToFourWeeksAndHalfMonthCredits() {
        List<ElapsedTimeScenario> scenarios = List.of(
                new ElapsedTimeScenario(
                        "2026-08-08T00:00:00Z",
                        7L,
                        23L,
                        "0.23",
                        "0.27"),
                new ElapsedTimeScenario(
                        "2026-08-15T00:00:00Z",
                        14L,
                        16L,
                        "0.16",
                        "0.34"),
                new ElapsedTimeScenario(
                        "2026-08-16T00:00:00Z",
                        15L,
                        15L,
                        "0.15",
                        "0.35"),
                new ElapsedTimeScenario(
                        "2026-08-22T00:00:00Z",
                        21L,
                        9L,
                        "0.09",
                        "0.41"),
                new ElapsedTimeScenario(
                        "2026-08-29T00:00:00Z",
                        28L,
                        2L,
                        "0.02",
                        "0.48"));

        for (ElapsedTimeScenario scenario : scenarios) {
            var quote = serviceAt(scenario.quoteAt()).quote(command(
                    MembershipTier.PRO,
                    MembershipTier.MAX,
                    "2026-08-01T00:00:00Z",
                    "2026-08-31T00:00:00Z",
                    "0.30"));

            assertThat(30L - quote.remainingDays())
                    .isEqualTo(scenario.elapsedDays());
            assertThat(quote.subscriptionDays()).isEqualTo(30L);
            assertThat(quote.remainingDays())
                    .isEqualTo(scenario.remainingDays());
            assertThat(quote.targetPlanPriceYuan())
                    .isEqualByComparingTo("0.50");
            assertThat(quote.creditAmountYuan())
                    .isEqualByComparingTo(scenario.creditAmount());
            assertThat(quote.payAmountYuan())
                    .isEqualByComparingTo(scenario.payAmount());
        }
    }

    @Test
    void fullyElapsedProSubscriptionBecomesNewMaxPurchaseAtFullPrice() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        var policy = new MembershipTransitionPolicyImpl(clock);
        var priceService = new FixedMembershipPlanPriceServiceImpl();
        var service = new MembershipUpgradeQuoteServiceImpl(
                policy, priceService, clock);
        MembershipUpgradeQuoteCommand command = command(
                MembershipTier.PRO,
                MembershipTier.MAX,
                "2026-08-01T00:00:00Z",
                "2026-08-31T00:00:00Z",
                "0.30");

        assertThatThrownBy(() -> service.quote(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type=NEW_PURCHASE");
        assertThat(priceService.getRequiredPrice(MembershipTier.MAX))
                .isEqualByComparingTo("0.50");
    }

    @Test
    void halfMonthUpgradeUsesActualPaidAmountAndRoundsPayableUp() {
        var quote = serviceAt("2026-08-16T12:00:00Z").quote(command(
                MembershipTier.GO,
                MembershipTier.PLUS,
                "2026-08-01T00:00:00Z",
                "2026-08-31T00:00:00Z",
                "0.05"));

        assertThat(quote.targetPlanPriceYuan())
                .isEqualByComparingTo("0.20");
        assertThat(quote.creditAmountYuan())
                .isEqualByComparingTo("0.02");
        assertThat(quote.payAmountYuan())
                .isEqualByComparingTo("0.18");
        assertThat(quote.subscriptionDays()).isEqualTo(30L);
        assertThat(quote.remainingDays()).isEqualTo(15L);
        assertThat(quote.quotedAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-16T12:00:00Z"));
    }

    @Test
    void currentUtcDateCountsAsOneRemainingDayBeforeExactExpiration() {
        var quote = serviceAt("2026-08-31T01:00:00Z").quote(command(
                MembershipTier.PLUS,
                MembershipTier.PRO,
                "2026-07-31T12:00:00Z",
                "2026-08-31T12:00:00Z",
                "0.20"));

        assertThat(quote.subscriptionDays()).isEqualTo(31L);
        assertThat(quote.remainingDays()).isEqualTo(1L);
        assertThat(quote.payAmountYuan()).isEqualByComparingTo("0.30");
        assertThat(quote.creditAmountYuan()).isEqualByComparingTo("0.00");
    }

    @Test
    void proToMaxProrationUsesActualTwentyEightToThirtyOneDayMonthLengths() {
        List<MonthScenario> scenarios = List.of(
                new MonthScenario(
                        "2025-01-31T00:00:00Z",
                        "2025-02-28T00:00:00Z",
                        "2025-02-14T00:00:00Z",
                        28L,
                        14L,
                        "0.15",
                        "0.35"),
                new MonthScenario(
                        "2024-01-31T00:00:00Z",
                        "2024-02-29T00:00:00Z",
                        "2024-02-15T00:00:00Z",
                        29L,
                        14L,
                        "0.14",
                        "0.36"),
                new MonthScenario(
                        "2026-09-30T00:00:00Z",
                        "2026-10-30T00:00:00Z",
                        "2026-10-15T00:00:00Z",
                        30L,
                        15L,
                        "0.15",
                        "0.35"),
                new MonthScenario(
                        "2026-08-01T00:00:00Z",
                        "2026-09-01T00:00:00Z",
                        "2026-08-17T00:00:00Z",
                        31L,
                        15L,
                        "0.14",
                        "0.36"));

        for (MonthScenario scenario : scenarios) {
            var quote = serviceAt(scenario.quoteAt()).quote(command(
                    MembershipTier.PRO,
                    MembershipTier.MAX,
                    scenario.startedAt(),
                    scenario.expiresAt(),
                    "0.30"));

            assertThat(quote.subscriptionDays())
                    .isEqualTo(scenario.subscriptionDays());
            assertThat(quote.remainingDays())
                    .isEqualTo(scenario.remainingDays());
            assertThat(quote.targetPlanPriceYuan())
                    .isEqualByComparingTo("0.50");
            assertThat(quote.creditAmountYuan())
                    .isEqualByComparingTo(scenario.creditAmount());
            assertThat(quote.payAmountYuan())
                    .isEqualByComparingTo(scenario.payAmount());
        }
    }

    @Test
    void oldActualPaidAmountIsUsedInsteadOfTheOldCatalogPrice() {
        var quote = serviceAt("2026-08-16T12:00:00Z").quote(command(
                MembershipTier.GO,
                MembershipTier.PLUS,
                "2026-08-01T00:00:00Z",
                "2026-08-31T00:00:00Z",
                "0.03"));

        assertThat(quote.payAmountYuan()).isEqualByComparingTo("0.19");
        assertThat(quote.creditAmountYuan()).isEqualByComparingTo("0.01");
    }

    @Test
    void payableNeverBecomesNegativeWhenCreditExceedsTargetPrice() {
        var quote = serviceAt("2026-08-02T00:00:00Z").quote(command(
                MembershipTier.GO,
                MembershipTier.PLUS,
                "2026-08-01T00:00:00Z",
                "2026-08-31T00:00:00Z",
                "9.99"));

        assertThat(quote.payAmountYuan()).isEqualByComparingTo("0.00");
        assertThat(quote.creditAmountYuan()).isEqualByComparingTo("0.20");
    }

    @Test
    void nonUpgradeTransitionDoesNotProduceAQuote() {
        assertThatThrownBy(() -> serviceAt("2026-08-16T12:00:00Z").quote(command(
                MembershipTier.PLUS,
                MembershipTier.GO,
                "2026-08-01T00:00:00Z",
                "2026-08-31T00:00:00Z",
                "0.20")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DOWNGRADE_NOT_ALLOWED");
    }

    @Test
    void invalidPeriodInactiveSubscriptionAndInvalidAmountAreRejected() {
        assertThatThrownBy(() -> command(
                MembershipTier.GO,
                MembershipTier.PLUS,
                "2026-08-31T00:00:00Z",
                "2026-08-01T00:00:00Z",
                "0.05"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start before expiration");

        assertThatThrownBy(() -> serviceAt("2026-07-31T23:59:59Z").quote(command(
                MembershipTier.GO,
                MembershipTier.PLUS,
                "2026-08-01T00:00:00Z",
                "2026-08-31T00:00:00Z",
                "0.05")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");

        assertThatThrownBy(() -> command(
                MembershipTier.GO,
                MembershipTier.PLUS,
                "2026-08-01T00:00:00Z",
                "2026-08-31T00:00:00Z",
                "-0.01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");

        assertThatThrownBy(() -> command(
                MembershipTier.GO,
                MembershipTier.PLUS,
                "2026-08-01T00:00:00Z",
                "2026-08-31T00:00:00Z",
                "0.001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two decimal places");

        assertThatNullPointerException().isThrownBy(() ->
                new MembershipUpgradeQuoteCommand(
                        MembershipTier.GO,
                        MembershipTier.PLUS,
                        null,
                        OffsetDateTime.parse("2026-08-31T00:00:00Z"),
                        new BigDecimal("0.05")));
        assertThatNullPointerException().isThrownBy(() ->
                new MembershipUpgradeQuoteCommand(
                        MembershipTier.GO,
                        MembershipTier.PLUS,
                        OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                        OffsetDateTime.parse("2026-08-31T00:00:00Z"),
                        null));
    }

    private static MembershipUpgradeQuoteServiceImpl serviceAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new MembershipUpgradeQuoteServiceImpl(
                new MembershipTransitionPolicyImpl(clock),
                new FixedMembershipPlanPriceServiceImpl(),
                clock);
    }

    private static MembershipUpgradeQuoteCommand command(
            MembershipTier current,
            MembershipTier target,
            String startedAt,
            String expiresAt,
            String paidAmount) {
        return new MembershipUpgradeQuoteCommand(
                current,
                target,
                OffsetDateTime.parse(startedAt),
                OffsetDateTime.parse(expiresAt),
                new BigDecimal(paidAmount));
    }

    private record MonthScenario(
            String startedAt,
            String expiresAt,
            String quoteAt,
            long subscriptionDays,
            long remainingDays,
            String creditAmount,
            String payAmount) {
    }

    private record ElapsedTimeScenario(
            String quoteAt,
            long elapsedDays,
            long remainingDays,
            String creditAmount,
            String payAmount) {
    }
}
