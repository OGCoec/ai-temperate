package com.example.temperate.model.user.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示与登录身份关联的用户资料持久化实体。
 *
 * <p>该实体承载显示名、当前头像引用和账号状态等资料字段；会员等级与额度由独立实体负责，
 * 头像对象移动、认证判断和资料更新规则由 Service 层负责。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class UserProfile {

    private Long id;
    private Long loginIdentityId;
    private String displayName;
    private String avatarUrl;
    private Integer accountStatus;
}
