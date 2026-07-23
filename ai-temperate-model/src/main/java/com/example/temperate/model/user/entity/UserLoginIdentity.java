package com.example.temperate.model.user.entity;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示用户登录身份在持久化层中的数据实体。
 *
 * <p>该实体保存规范化联系方式、密码哈希和认证版本等数据库字段；校验、脱敏和外部响应转换必须在上层完成。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class UserLoginIdentity {

    private Long id;
    private String email;
    private String phone;
    private String passwordHash;
    private Long passwordVersion;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

}
