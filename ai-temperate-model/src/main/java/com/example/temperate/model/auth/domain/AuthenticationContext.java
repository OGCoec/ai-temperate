package com.example.temperate.model.auth.domain;

import lombok.Getter;
import com.example.temperate.model.auth.enums.AccountStatus;

/**
 * 封装认证流程判断账号可用性和签发会话所需的身份快照。
 *
 * <p>该模型可携带密码哈希等敏感认证材料，仅限受控认证流程内部使用；会员等级和额度不参与认证，不进入该上下文或外部 API 响应。</p>
 */
@Getter
public final class AuthenticationContext {

    private final long identityId;
    private final String passwordHash;
    private final long passwordVersion;
    private final AccountStatus accountStatus;
    private final String displayName;
    private final String email;
    private final String phone;

    public AuthenticationContext(
            long identityId,
            String passwordHash,
            long passwordVersion,
            AccountStatus accountStatus,
            String displayName) {
        this(identityId, passwordHash, passwordVersion, accountStatus, displayName, null, null);
    }

    public AuthenticationContext(
            long identityId,
            String passwordHash,
            long passwordVersion,
            AccountStatus accountStatus,
            String displayName,
            String email,
            String phone) {
        this.identityId = identityId;
        this.passwordHash = passwordHash;
        this.passwordVersion = passwordVersion;
        this.accountStatus = accountStatus;
        this.displayName = displayName;
        this.email = email;
        this.phone = phone;
    }

}
