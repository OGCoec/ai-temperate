package com.example.temperate.service.user.avatar;

/**
 * 表示头像 URL 在数据库事务中的激活结果。
 */
public record AvatarActivation(
        long userId,
        String avatarUrl) {
}
