package com.example.temperate.service.user.membership.purchase;

/**
 * 该策略是来根据当前会员的有效等级判断首购、个人套餐升级和禁止转换。
 *
 * <p>它只进行纯规则判断，不读取或更新数据库，也不创建订单或发起支付。</p>
 */
public interface MembershipTransitionPolicy {

    /**
     * 使用当前 UTC 时间先计算有效等级，再返回本次目标等级的转换决策。
     */
    MembershipTransitionDecision evaluate(MembershipTransitionCommand command);
}
