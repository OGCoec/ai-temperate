package com.example.temperate.service.user.membership.payment.offer;

/**
 * 该服务是来为已认证用户只读汇总当前可购买的个人会员套餐及服务端报价。
 *
 * <p>实现不得创建订单、惰性更新会员到期状态或发放会员权益。</p>
 */
public interface MembershipPlanOfferService {

    MembershipPlanOfferResult getOffers(long loginIdentityId);
}
