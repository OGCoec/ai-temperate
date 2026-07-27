package com.example.temperate.service.user.avatar;

/**
 * 表示头像已经复制并成功写入数据库后的同步确认结果。
 */
public record AvatarConfirmation(String avatarUrl) {
}
