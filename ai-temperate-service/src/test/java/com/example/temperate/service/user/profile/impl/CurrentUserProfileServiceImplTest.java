package com.example.temperate.service.user.profile.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.user.domain.CurrentUserProfile;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证当前用户资料服务只按已认证的内部用户 ID 查询必要展示字段，并正确处理账号已不可用的情况。
 */
class CurrentUserProfileServiceImplTest {

    private UserLoginIdentityMapper identityMapper;
    private CurrentUserProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        identityMapper = mock(UserLoginIdentityMapper.class);
        service = new CurrentUserProfileServiceImpl(identityMapper);
    }

    @Test
    void returnsTheMinimalProfileForTheAuthenticatedUser() {
        CurrentUserProfile expected = new CurrentUserProfile(
                "Alice", "alice@example.test", "+14155550123");
        when(identityMapper.findCurrentUserProfileById(10001L)).thenReturn(expected);

        CurrentUserProfile result = service.getRequired(10001L);

        assertThat(result).isEqualTo(expected);
        verify(identityMapper).findCurrentUserProfileById(10001L);
    }

    @Test
    void rejectsADeletedOrUnavailableAuthenticatedAccount() {
        when(identityMapper.findCurrentUserProfileById(10001L)).thenReturn(null);

        assertThatThrownBy(() -> service.getRequired(10001L))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(SessionAuthenticationErrorCode.ACCOUNT_UNAVAILABLE);
                    assertThat(exception.clearCookies()).isTrue();
                });
    }
}
