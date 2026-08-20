package com.example.temperate.service.user.membership.purchase;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.util.Objects;

/**
 * 该结果是来同时返回惰性到期后的有效等级、转换类型和稳定拒绝原因。
 */
public record MembershipTransitionDecision(
        MembershipTier effectiveCurrentTier,
        MembershipTier targetTier,
        MembershipTransitionType transitionType,
        MembershipTransitionRejectionReason rejectionReason) {

    public MembershipTransitionDecision {
        Objects.requireNonNull(
                effectiveCurrentTier,
                "Effective current membership tier is required.");
        Objects.requireNonNull(targetTier, "Target membership tier is required.");
        Objects.requireNonNull(transitionType, "Membership transition type is required.");
        Objects.requireNonNull(rejectionReason, "Membership rejection reason is required.");
        boolean rejected = transitionType == MembershipTransitionType.REJECTED;
        if (rejected == (rejectionReason == MembershipTransitionRejectionReason.NONE)) {
            throw new IllegalArgumentException(
                    "Rejected transitions require a reason and allowed transitions require NONE.");
        }
    }
}
