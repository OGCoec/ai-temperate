package com.example.temperate.service.auth.oauth.identity.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.enums.RegistrationSource;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.identity.OAuthSubjectBindingStrategy;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 使用固定 {@code google_subject} 列实现 Google OIDC Subject 查询与幂等绑定。
 */
@Component("googleOAuthSubjectBindingStrategy")
public final class GoogleOAuthSubjectBindingStrategy implements OAuthSubjectBindingStrategy {

    private final UserLoginIdentityMapper identityMapper;

    public GoogleOAuthSubjectBindingStrategy(UserLoginIdentityMapper identityMapper) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public RegistrationSource registrationSource() {
        return RegistrationSource.GOOGLE;
    }

    @Override
    public UserLoginIdentity findBySubject(String subject) {
        return identityMapper.findByGoogleSubject(subject);
    }

    @Override
    public String subjectOf(UserLoginIdentity identity) {
        return identity.getGoogleSubject();
    }

    @Override
    public void applySubject(UserLoginIdentity identity, String subject) {
        identity.setGoogleSubject(subject);
    }

    @Override
    public int bindIfAbsent(long identityId, String subject) {
        return identityMapper.bindGoogleSubjectIfAbsent(identityId, subject);
    }
}
