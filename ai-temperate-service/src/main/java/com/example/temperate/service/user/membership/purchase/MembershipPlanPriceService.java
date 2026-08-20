package com.example.temperate.service.user.membership.purchase;

import com.example.temperate.model.auth.enums.MembershipTier;
import java.math.BigDecimal;

/**
 * 该服务是来为纯会员报价规则提供服务端可信的付费套餐价格。
 *
 * <p>当前实现使用测试价格；未来接入生产价格时替换实现即可，调用方不得提交价格。</p>
 */
public interface MembershipPlanPriceService {

    /**
     * 返回指定付费套餐精确到两位小数的价格；FREE 不是可购买套餐。
     */
    BigDecimal getRequiredPrice(MembershipTier membershipTier);
}
