package com.example.temperate.service.user.membership;

import com.example.temperate.model.auth.enums.MembershipTier;

/**
 * 为注册、资料投影和模型计费统一提供全部会员等级的额度周期规则。
 *
 * <p>实现必须在应用启动时完整校验所有等级，调用方不再自行提供套餐默认值。</p>
 */
public interface MembershipQuotaPlanService {

    MembershipQuotaPlan getRequired(MembershipTier membershipTier);
}
