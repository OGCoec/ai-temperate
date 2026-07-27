package com.example.temperate.service.user.avatar.impl;

import com.example.temperate.mapper.user.avatar.UserAvatarMapper;
import com.example.temperate.model.user.domain.UserAvatarState;
import com.example.temperate.service.user.avatar.AvatarActivation;
import com.example.temperate.service.user.avatar.UserAvatarErrorCode;
import com.example.temperate.service.user.avatar.UserAvatarException;
import com.example.temperate.service.user.avatar.UserAvatarPersistenceService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在 PostgreSQL 本地事务中锁定用户资料行并切换当前头像 URL。
 *
 * <p>OSS I/O 不进入该事务；旧头像和临时对象不在数据库中登记清理任务。</p>
 */
@Service
public final class UserAvatarPersistenceServiceImpl implements UserAvatarPersistenceService {

    private final UserAvatarMapper avatarMapper;

    public UserAvatarPersistenceServiceImpl(UserAvatarMapper avatarMapper) {
        this.avatarMapper = avatarMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public AvatarActivation findCurrent(long userId) {
        UserAvatarState current = avatarMapper.findByUserId(userId);
        if (current == null) {
            return null;
        }
        return new AvatarActivation(
                userId,
                current.avatarUrl());
    }

    @Override
    @Transactional
    public AvatarActivation activate(
            long userId,
            String avatarUrl) {
        // 行锁使同一用户的两个确认请求串行化，避免后完成的旧请求覆盖已经提交的新 URL。
        UserAvatarState current = avatarMapper.findByUserIdForUpdate(userId);
        if (current == null) {
            throw new UserAvatarException(
                    UserAvatarErrorCode.PROFILE_UNAVAILABLE,
                    "当前用户资料不存在或不可用。");
        }

        if (Objects.equals(avatarUrl, current.avatarUrl())) {
            return new AvatarActivation(
                    userId,
                    current.avatarUrl());
        }

        int affected = avatarMapper.updateAvatar(userId, avatarUrl);
        if (affected != 1) {
            throw new UserAvatarException(
                    UserAvatarErrorCode.PERSISTENCE_FAILED,
                    "头像资料更新未影响预期的一行数据。");
        }

        return new AvatarActivation(
                userId,
                avatarUrl);
    }
}
