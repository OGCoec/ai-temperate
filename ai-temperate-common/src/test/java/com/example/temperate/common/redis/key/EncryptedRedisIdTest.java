package com.example.temperate.common.redis.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 验证 Redis 加密资源标识只接受固定长度的规范 Base64URL 文本。
 */
final class EncryptedRedisIdTest {

    @Test
    void acceptsTwentyTwoCharacterBase64UrlIdentifier() {
        EncryptedRedisId identifier =
                new EncryptedRedisId("I11B5RV16PBmGzFJEwJf3g");

        assertEquals("I11B5RV16PBmGzFJEwJf3g", identifier.value());
    }

    @Test
    void rejectsMissingWrongLengthOrNonUrlSafeIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> new EncryptedRedisId(null));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedRedisId(""));
        assertThrows(IllegalArgumentException.class,
                () -> new EncryptedRedisId("I11B5RV16PBmGzFJEwJf3"));
        assertThrows(IllegalArgumentException.class,
                () -> new EncryptedRedisId("I11B5RV16PBmGzFJEwJf3+"));
    }
}
