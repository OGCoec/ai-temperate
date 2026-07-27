package com.example.temperate.service.user.avatar;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 只用受信任用户公共 ID、24 位 NanoID 和白名单格式构造头像 OSS Object Key。
 *
 * <p>客户端路径和 URL 永不参与拼接，从而把跨用户覆盖与目录穿越阻断在业务边界。</p>
 */
@Component
public final class AvatarObjectKeyFactory {

    private static final Pattern PUBLIC_USER_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern IMAGE_ID = Pattern.compile("^[A-Za-z0-9_-]{24}$");
    private static final String USER_PREFIX = "ai-temperate/user/";

    public String temporaryKey(
            String publicUserId,
            String imageId,
            AvatarImageFormat format) {
        return USER_PREFIX + "temp/" + requirePublicUserId(publicUserId) + "/"
                + requireImageId(imageId) + "." + requireFormat(format).extension();
    }

    public String finalKey(
            String publicUserId,
            String imageId,
            AvatarImageFormat format) {
        return USER_PREFIX + requirePublicUserId(publicUserId) + "/"
                + requireImageId(imageId) + "." + requireFormat(format).extension();
    }

    public String requireImageId(String imageId) {
        if (imageId == null || !IMAGE_ID.matcher(imageId).matches()) {
            throw new IllegalArgumentException("imageId must be a canonical 24-character NanoID");
        }
        return imageId;
    }

    private static String requirePublicUserId(String publicUserId) {
        if (publicUserId == null || !PUBLIC_USER_ID.matcher(publicUserId).matches()) {
            throw new IllegalArgumentException("publicUserId must be a canonical 11-character Base64URL ID");
        }
        return publicUserId;
    }

    private static AvatarImageFormat requireFormat(AvatarImageFormat format) {
        return Objects.requireNonNull(format, "format must not be null");
    }
}
