package com.example.temperate.service.user.membership.purchase.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionCommand;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionRejectionReason;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定七档会员的首购、个人链升级、封闭套餐和到期回退决策规则。
 */
final class MembershipTransitionPolicyImplTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime FUTURE_EXPIRATION =
            OffsetDateTime.parse("2026-09-20T12:00:00Z");

    private final MembershipTransitionPolicyImpl policy =
            new MembershipTransitionPolicyImpl(CLOCK);

    @Test
    void freeCanStartAnyPaidMembership() {
        for (MembershipTier target : List.of(
                MembershipTier.GO,
                MembershipTier.EDU,
                MembershipTier.TEAM,
                MembershipTier.PLUS,
                MembershipTier.PRO,
                MembershipTier.MAX)) {
            var decision = policy.evaluate(new MembershipTransitionCommand(
                    MembershipTier.FREE, target, null));

            assertThat(decision.effectiveCurrentTier())
                    .isEqualTo(MembershipTier.FREE);
            assertThat(decision.targetTier()).isEqualTo(target);
            assertThat(decision.transitionType())
                    .isEqualTo(MembershipTransitionType.NEW_PURCHASE);
            assertThat(decision.rejectionReason())
                    .isEqualTo(MembershipTransitionRejectionReason.NONE);
        }
    }

    @Test
    void personalMembershipAllowsOnlyExplicitUpwardTransitions() {
        assertUpgrade(MembershipTier.GO, MembershipTier.PLUS);
        assertUpgrade(MembershipTier.GO, MembershipTier.PRO);
        assertUpgrade(MembershipTier.GO, MembershipTier.MAX);
        assertUpgrade(MembershipTier.PLUS, MembershipTier.PRO);
        assertUpgrade(MembershipTier.PLUS, MembershipTier.MAX);
        assertUpgrade(MembershipTier.PRO, MembershipTier.MAX);
    }

    @Test
    void sameTierAndLowerPersonalTierAreRejected() {
        assertRejected(
                MembershipTier.GO,
                MembershipTier.GO,
                MembershipTransitionRejectionReason.SAME_TIER_NOT_RENEWABLE);
        assertRejected(
                MembershipTier.PRO,
                MembershipTier.PLUS,
                MembershipTransitionRejectionReason.DOWNGRADE_NOT_ALLOWED);
        assertRejected(
                MembershipTier.MAX,
                MembershipTier.GO,
                MembershipTransitionRejectionReason.DOWNGRADE_NOT_ALLOWED);
    }

    @Test
    void personalAndLockedMembershipChainsCannotBeCrossed() {
        assertRejected(
                MembershipTier.GO,
                MembershipTier.EDU,
                MembershipTransitionRejectionReason.CROSS_CHAIN_NOT_ALLOWED);
        assertRejected(
                MembershipTier.PLUS,
                MembershipTier.TEAM,
                MembershipTransitionRejectionReason.CROSS_CHAIN_NOT_ALLOWED);
    }

    @Test
    void activeEducationAndTeamMembershipsCannotSwitch() {
        for (MembershipTier current : List.of(
                MembershipTier.EDU, MembershipTier.TEAM)) {
            for (MembershipTier target : List.of(
                    MembershipTier.GO,
                    MembershipTier.EDU,
                    MembershipTier.TEAM,
                    MembershipTier.PLUS,
                    MembershipTier.PRO,
                    MembershipTier.MAX)) {
                assertRejected(
                        current,
                        target,
                        MembershipTransitionRejectionReason
                                .LOCKED_TIER_NOT_SWITCHABLE);
            }
        }
    }

    @Test
    void targetFreeIsNeverPurchasable() {
        var decision = policy.evaluate(new MembershipTransitionCommand(
                MembershipTier.PLUS,
                MembershipTier.FREE,
                FUTURE_EXPIRATION));

        assertThat(decision.transitionType())
                .isEqualTo(MembershipTransitionType.REJECTED);
        assertThat(decision.rejectionReason())
                .isEqualTo(MembershipTransitionRejectionReason
                        .TARGET_FREE_NOT_PURCHASABLE);
    }

    @Test
    void expiredEqualBoundaryAndMissingPaidExpirationAreTreatedAsFree() {
        for (OffsetDateTime expiration : List.of(
                OffsetDateTime.parse("2026-08-20T11:59:59Z"),
                OffsetDateTime.parse("2026-08-20T12:00:00Z"))) {
            assertExpiredTierStartsNewPurchase(MembershipTier.PRO, expiration);
        }
        assertExpiredTierStartsNewPurchase(MembershipTier.EDU, null);
    }

    @Test
    void missingCurrentOrTargetTierIsRejectedAtTheCommandBoundary() {
        assertThatNullPointerException().isThrownBy(() ->
                new MembershipTransitionCommand(
                        null, MembershipTier.PRO, FUTURE_EXPIRATION));
        assertThatNullPointerException().isThrownBy(() ->
                new MembershipTransitionCommand(
                        MembershipTier.GO, null, FUTURE_EXPIRATION));
    }

    private void assertUpgrade(
            MembershipTier current,
            MembershipTier target) {
        var decision = policy.evaluate(new MembershipTransitionCommand(
                current, target, FUTURE_EXPIRATION));

        assertThat(decision.effectiveCurrentTier()).isEqualTo(current);
        assertThat(decision.transitionType())
                .isEqualTo(MembershipTransitionType.UPGRADE);
        assertThat(decision.rejectionReason())
                .isEqualTo(MembershipTransitionRejectionReason.NONE);
    }

    private void assertRejected(
            MembershipTier current,
            MembershipTier target,
            MembershipTransitionRejectionReason reason) {
        var decision = policy.evaluate(new MembershipTransitionCommand(
                current, target, FUTURE_EXPIRATION));

        assertThat(decision.effectiveCurrentTier()).isEqualTo(current);
        assertThat(decision.transitionType())
                .isEqualTo(MembershipTransitionType.REJECTED);
        assertThat(decision.rejectionReason()).isEqualTo(reason);
    }

    private void assertExpiredTierStartsNewPurchase(
            MembershipTier current,
            OffsetDateTime expiration) {
        var decision = policy.evaluate(new MembershipTransitionCommand(
                current, MembershipTier.MAX, expiration));

        assertThat(decision.effectiveCurrentTier())
                .isEqualTo(MembershipTier.FREE);
        assertThat(decision.transitionType())
                .isEqualTo(MembershipTransitionType.NEW_PURCHASE);
        assertThat(decision.rejectionReason())
                .isEqualTo(MembershipTransitionRejectionReason.NONE);
    }
}
