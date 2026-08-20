package com.example.temperate.service.user.membership.purchase;

/**
 * 该服务是来为合法个人套餐升级计算按剩余 UTC 自然日抵扣后的报价。
 *
 * <p>它不读取额度余额，不生成订单，也不执行支付或会员权益变更。</p>
 */
public interface MembershipUpgradeQuoteService {

    /**
     * 校验转换规则和有效订阅周期后，返回最终向上保留两位小数的升级应付金额。
     */
    MembershipUpgradeQuote quote(MembershipUpgradeQuoteCommand command);
}
