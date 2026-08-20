package com.example.temperate.service.user.membership.purchase.impl;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionCommand;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionDecision;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionPolicy;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionRejectionReason;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 该实现是来显式建模个人套餐升级链，并把已到期或缺少到期时间的付费等级按 FREE 判断。
 *
 * <p>EDU、TEAM 不参与个人等级排序；显式等级表避免数据库编码顺序被错误解释为升级顺序。</p>
 */
@Service
public final class MembershipTransitionPolicyImpl
        implements MembershipTransitionPolicy {

    private static final Set<MembershipTier> LOCKED_TIERS =
            Set.of(MembershipTier.EDU, MembershipTier.TEAM);
    private static final Map<MembershipTier, Integer> PERSONAL_RANKS =
            personalRanks();

    private final Clock clock;

    public MembershipTransitionPolicyImpl(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public MembershipTransitionDecision evaluate(
            MembershipTransitionCommand command) {
        Objects.requireNonNull(command, "Membership transition command is required.");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        MembershipTier current = effectiveCurrentTier(command, now);
        MembershipTier target = command.targetTier();

        if (target == MembershipTier.FREE) {
            return rejected(
                    current,
                    target,
                    MembershipTransitionRejectionReason
                            .TARGET_FREE_NOT_PURCHASABLE);
        }
        if (current == MembershipTier.FREE) {
            return allowed(current, target, MembershipTransitionType.NEW_PURCHASE);
        }
        if (LOCKED_TIERS.contains(current)) {
            return rejected(
                    current,
                    target,
                    MembershipTransitionRejectionReason
                            .LOCKED_TIER_NOT_SWITCHABLE);
        }
        if (LOCKED_TIERS.contains(target)) {
            return rejected(
                    current,
                    target,
                    MembershipTransitionRejectionReason.CROSS_CHAIN_NOT_ALLOWED);
        }
        if (current == target) {
            return rejected(
                    current,
                    target,
                    MembershipTransitionRejectionReason.SAME_TIER_NOT_RENEWABLE);
        }

        Integer currentRank = PERSONAL_RANKS.get(current);
        Integer targetRank = PERSONAL_RANKS.get(target);
        if (currentRank == null || targetRank == null) {
            throw new IllegalStateException("Membership transition chain is incomplete.");
        }
        if (targetRank > currentRank) {
            return allowed(current, target, MembershipTransitionType.UPGRADE);
        }
        return rejected(
                current,
                target,
                MembershipTransitionRejectionReason.DOWNGRADE_NOT_ALLOWED);
    }

    private static MembershipTier effectiveCurrentTier(
            MembershipTransitionCommand command,
            OffsetDateTime now) {
        if (command.currentTier() == MembershipTier.FREE) {
            return MembershipTier.FREE;
        }
        OffsetDateTime expiration = command.membershipExpiresAt();
        return expiration != null && expiration.isAfter(now)
                ? command.currentTier()
                : MembershipTier.FREE;
    }

    private static MembershipTransitionDecision allowed(
            MembershipTier current,
            MembershipTier target,
            MembershipTransitionType type) {
        return new MembershipTransitionDecision(
                current,
                target,
                type,
                MembershipTransitionRejectionReason.NONE);
    }

    private static MembershipTransitionDecision rejected(
            MembershipTier current,
            MembershipTier target,
            MembershipTransitionRejectionReason reason) {
        return new MembershipTransitionDecision(
                current,
                target,
                MembershipTransitionType.REJECTED,
                reason);
    }

    private static Map<MembershipTier, Integer> personalRanks() {
        EnumMap<MembershipTier, Integer> ranks =
                new EnumMap<>(MembershipTier.class);
        ranks.put(MembershipTier.GO, 0);
        ranks.put(MembershipTier.PLUS, 1);
        ranks.put(MembershipTier.PRO, 2);
        ranks.put(MembershipTier.MAX, 3);
        return Map.copyOf(ranks);
    }
}
