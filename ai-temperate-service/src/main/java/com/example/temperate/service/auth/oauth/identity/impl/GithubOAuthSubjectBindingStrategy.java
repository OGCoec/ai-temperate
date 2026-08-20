package com.example.temperate.service.auth.oauth.identity.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.enums.RegistrationSource;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.identity.OAuthSubjectBindingStrategy;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 使用固定 {@code github_subject} 列实现 GitHub 稳定主体查询与幂等绑定。
 */
@Component("githubOAuthSubjectBindingStrategy")
public final class GithubOAuthSubjectBindingStrategy implements OAuthSubjectBindingStrategy {

    private final UserLoginIdentityMapper identityMapper;

    public GithubOAuthSubjectBindingStrategy(UserLoginIdentityMapper identityMapper) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GITHUB;
    }

    @Override
    public RegistrationSource registrationSource() {
        return RegistrationSource.GITHUB;
    }

    @Override
    public UserLoginIdentity findBySubject(String subject) {
        return identityMapper.findByGithubSubject(subject);
    }

    @Override
    public String subjectOf(UserLoginIdentity identity) {
        return identity.getGithubSubject();
    }

    @Override
    public void applySubject(UserLoginIdentity identity, String subject) {
        identity.setGithubSubject(subject);
    }

    @Override
    public int bindIfAbsent(long identityId, String subject) {
        return identityMapper.bindGithubSubjectIfAbsent(identityId, subject);
    }
}
