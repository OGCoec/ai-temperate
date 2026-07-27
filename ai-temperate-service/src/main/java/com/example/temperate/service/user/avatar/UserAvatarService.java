package com.example.temperate.service.user.avatar;

/**
 * 定义普通用户创建预上传、取消预上传和同步确认头像的业务能力。
 */
public interface UserAvatarService {

    AvatarPreupload createPreupload(
            long userId,
            String publicUserId,
            AvatarImageFormat format,
            long sizeBytes);

    void cancel(
            long userId,
            String publicUserId,
            String preuploadId,
            AvatarImageFormat format);

    AvatarConfirmation confirm(
            long userId,
            String publicUserId,
            String preuploadId,
            AvatarImageFormat format);
}
