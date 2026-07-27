package com.example.temperate.model.user.domain;

/**
 * 表示用户资料表中的当前头像状态，用于行锁更新与重复确认判断。
 *
 * <p>该对象只携带内部用户 ID 和公开 URL，不持久化 OSS Object Key、预上传状态、Token 或图片内容。</p>
 */
public record UserAvatarState(
        long userId,
        String avatarUrl) {
}
