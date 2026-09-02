package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.user.membership.payment.MembershipOrder;
import java.util.Objects;

/**
 * 该结果是来返回数据库幂等创建的胜出订单，并区分是否实际插入新行或需要强制替换另一笔活动订单。
 */
public record MembershipOrderCreationResult(
        MembershipOrder order,
        boolean created,
        boolean replacementRequired) {

    public MembershipOrderCreationResult {
        order = Objects.requireNonNull(order, "order must not be null");
        if (created && replacementRequired) {
            throw new IllegalArgumentException(
                    "A newly created order cannot require replacement.");
        }
    }

    /** 保持既有创建与幂等重放调用点兼容；这两种结果都不要求替换。 */
    public MembershipOrderCreationResult(MembershipOrder order, boolean created) {
        this(order, created, false);
    }
}
