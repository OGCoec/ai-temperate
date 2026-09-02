package com.example.temperate.service.user.membership.payment.order;

import java.util.function.Supplier;

/**
 * 该服务是来串行化同一登录身份的会员订单创建与强制替换，避免不同幂等键并发互相终结胜出订单。
 */
public interface MembershipOrderCreationLockService {

    <T> T execute(long loginIdentityId, Supplier<T> action);
}
