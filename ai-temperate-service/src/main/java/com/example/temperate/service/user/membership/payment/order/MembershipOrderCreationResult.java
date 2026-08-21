package com.example.temperate.service.user.membership.payment.order;

import com.example.temperate.model.user.membership.payment.MembershipOrder;
import java.util.Objects;

/**
 * 该结果是来返回数据库幂等创建的胜出订单，并区分当前事务是否实际插入新行。
 */
public record MembershipOrderCreationResult(
        MembershipOrder order,
        boolean created) {

    public MembershipOrderCreationResult {
        order = Objects.requireNonNull(order, "order must not be null");
    }
}
