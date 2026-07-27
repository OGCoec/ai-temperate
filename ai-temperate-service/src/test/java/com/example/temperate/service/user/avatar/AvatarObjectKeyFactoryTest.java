package com.example.temperate.service.user.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证头像对象键只能由服务端使用当前认证用户公共 ID、规范 NanoID 与白名单格式构造。
 */
class AvatarObjectKeyFactoryTest {

    private final AvatarObjectKeyFactory factory = new AvatarObjectKeyFactory();

    @Test
    void buildsUserBoundTemporaryAndFinalKeysWithoutLeadingSlash() {
        String imageId = "0123456789_abcdefghijklm";

        assertThat(factory.temporaryKey("AAAAAAAAJxE", imageId, AvatarImageFormat.WEBP))
                .isEqualTo("ai-temperate/user/temp/AAAAAAAAJxE/" + imageId + ".webp");
        assertThat(factory.finalKey("AAAAAAAAJxE", imageId, AvatarImageFormat.WEBP))
                .isEqualTo("ai-temperate/user/AAAAAAAAJxE/" + imageId + ".webp");
    }

    @Test
    void rejectsNonCanonicalPublicIdAndNanoId() {
        assertThatThrownBy(() -> factory.temporaryKey(
                        "../other-user",
                        "0123456789_abcdefghijklm",
                        AvatarImageFormat.PNG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.finalKey(
                        "AAAAAAAAJxE",
                        "../../object",
                        AvatarImageFormat.PNG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.finalKey(
                        "AAAAAAAAJxE",
                        "0123456789_abcdefghijklmnopqrstuvwxyz-",
                        AvatarImageFormat.PNG))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
