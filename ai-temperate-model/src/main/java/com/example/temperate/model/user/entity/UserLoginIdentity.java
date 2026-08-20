package com.example.temperate.model.user.entity;

import com.example.temperate.model.auth.enums.RegistrationSource;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 表示用户登录身份在持久化层中的数据实体。
 *
 * <p>该实体保存规范化联系方式、密码哈希、认证版本以及 TOTP 启用状态和加密密钥等数据库字段；
 * TOTP 明文密钥不得通过该实体对外暴露，校验、解密和响应转换必须在上层完成。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class UserLoginIdentity {

    private Long id;
    private RegistrationSource registrationSource = RegistrationSource.STANDARD;
    private String githubSubject;
    private String googleSubject;
    private String email;
    private Boolean emailVerified = Boolean.FALSE;
    private String phone;
    private String passwordHash;
    private Long passwordVersion;
    private Boolean totpEnabled = Boolean.FALSE;
    private String totpSecretEncrypted;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

}
