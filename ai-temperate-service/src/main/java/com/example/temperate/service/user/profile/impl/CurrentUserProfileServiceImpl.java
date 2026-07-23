package com.example.temperate.service.user.profile.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.user.domain.CurrentUserProfile;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.user.profile.CurrentUserProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从用户身份表与资料表一次联查当前用户展示资料，并保持实现无状态且不读取认证凭据。
 */
@Service
public final class CurrentUserProfileServiceImpl implements CurrentUserProfileService {

    private final UserLoginIdentityMapper identityMapper;

    public CurrentUserProfileServiceImpl(UserLoginIdentityMapper identityMapper) {
        this.identityMapper = identityMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentUserProfile getRequired(long userId) {
        CurrentUserProfile profile = identityMapper.findCurrentUserProfileById(userId);
        if (profile == null) {
            // Access Token 通过后账号仍可能被并发删除或禁用，此处再次收敛为终止会话错误。
            throw new SessionAuthenticationException(
                    SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE,
                    "当前账号不可用，请重新登录。",
                    true);
        }
        return profile;
    }
}
