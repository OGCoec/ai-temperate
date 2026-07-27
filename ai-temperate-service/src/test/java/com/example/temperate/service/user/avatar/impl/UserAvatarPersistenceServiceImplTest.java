package com.example.temperate.service.user.avatar.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.avatar.UserAvatarMapper;
import com.example.temperate.model.user.domain.UserAvatarState;
import com.example.temperate.service.user.avatar.UserAvatarErrorCode;
import com.example.temperate.service.user.avatar.UserAvatarException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证头像激活事务在行锁内执行重复 URL 判断和单行更新。
 */
class UserAvatarPersistenceServiceImplTest {

    private UserAvatarMapper avatarMapper;
    private UserAvatarPersistenceServiceImpl service;

    @BeforeEach
    void setUp() {
        avatarMapper = mock(UserAvatarMapper.class);
        service = new UserAvatarPersistenceServiceImpl(avatarMapper);
    }

    @Test
    void replacesCurrentAvatarUrl() {
        when(avatarMapper.findByUserIdForUpdate(10001L))
                .thenReturn(new UserAvatarState(
                        10001L,
                        "https://cdn.example.test/old.webp"));
        when(avatarMapper.updateAvatar(
                        10001L,
                        "https://cdn.example.test/new.webp"))
                .thenReturn(1);

        var result = service.activate(
                10001L,
                "https://cdn.example.test/new.webp");

        assertThat(result.avatarUrl()).isEqualTo("https://cdn.example.test/new.webp");
        verify(avatarMapper).updateAvatar(10001L, "https://cdn.example.test/new.webp");
    }

    @Test
    void repeatedConfirmationDoesNotUpdateOrDeleteCurrentFinalObject() {
        when(avatarMapper.findByUserIdForUpdate(10001L))
                .thenReturn(new UserAvatarState(
                        10001L,
                        "https://cdn.example.test/new.webp"));

        var result = service.activate(
                10001L,
                "https://cdn.example.test/new.webp");

        assertThat(result.avatarUrl()).isEqualTo("https://cdn.example.test/new.webp");
        verify(avatarMapper, never()).updateAvatar(
                10001L,
                "https://cdn.example.test/new.webp");
    }

    @Test
    void rejectsUnexpectedUpdateCountSoTransactionCanRollBack() {
        when(avatarMapper.findByUserIdForUpdate(10001L))
                .thenReturn(new UserAvatarState(10001L, null));
        when(avatarMapper.updateAvatar(10001L, "url"))
                .thenReturn(0);

        assertThatThrownBy(() -> service.activate(
                        10001L,
                        "url"))
                .isInstanceOf(UserAvatarException.class)
                .extracting("code")
                .isEqualTo(UserAvatarErrorCode.PERSISTENCE_FAILED);
    }
}
