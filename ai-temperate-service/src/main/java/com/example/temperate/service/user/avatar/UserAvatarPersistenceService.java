package com.example.temperate.service.user.avatar;

/**
 * 定义头像当前状态读取与数据库事务激活边界。
 */
public interface UserAvatarPersistenceService {

    AvatarActivation findCurrent(long userId);

    AvatarActivation activate(
            long userId,
            String avatarUrl);
}
