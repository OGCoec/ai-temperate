package com.example.temperate.model.user.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示与登录身份一对一关联的当前会员等级、可用额度和额度周期边界持久化实体。
 *
 * <p>额度使用固定缩放比例 100 的最小单位整数保存，周期时间使用带偏移量的绝对时间；
 * 该实体不承担账号状态校验、额度展示换算或消费规则。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class UserMembershipQuota {

    private Long id;
    private Long loginIdentityId;
    private Integer membershipTier;
    private Long quotaBalanceMinor;
    private OffsetDateTime quotaPeriodStartedAt;
    private OffsetDateTime quotaPeriodEndsAt;
}
