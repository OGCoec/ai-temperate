package com.example.temperate.common.security.hmac;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证敏感标识的 HMAC 生成具有稳定性、规范格式和最小密钥长度约束。
 */
final class HmacSha256IdentifierTest {

    private static final byte[] TEST_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void createsDeterministicUnpaddedBase64UrlIdentifier() {
        HmacSha256Identifier identifier = new HmacSha256Identifier(TEST_KEY);

        HmacIdentifier actual = identifier.identify("alice@example.com");

        assertEquals("hBJA0qW2ZUs64h_ESZ23t4Zwd83WfD4WzvH5hD4n0fo", actual.value());
        assertEquals(43, actual.value().length());
        assertTrue(actual.value().matches("^[A-Za-z0-9_-]{43}$"));
    }

    @Test
    void changesIdentifierWhenNormalizedInputChanges() {
        HmacSha256Identifier identifier = new HmacSha256Identifier(TEST_KEY);

        assertNotEquals(identifier.identify("alice@example.com"),
                identifier.identify("bob@example.com"));
    }

    @Test
    void rejectsMissingOrShortSecrets() {
        assertThrows(IllegalArgumentException.class, () -> new HmacSha256Identifier(null));
        assertThrows(IllegalArgumentException.class, () -> new HmacSha256Identifier(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new HmacSha256Identifier(new byte[31]));
    }

    @Test
    void rejectsMissingOrBlankNormalizedIdentifiers() {
        HmacSha256Identifier identifier = new HmacSha256Identifier(TEST_KEY);

        assertThrows(IllegalArgumentException.class, () -> identifier.identify(null));
        assertThrows(IllegalArgumentException.class, () -> identifier.identify(""));
        assertThrows(IllegalArgumentException.class, () -> identifier.identify("   "));
    }

    @Test
    void defensivelyCopiesSecretBytes() {
        byte[] mutableKey = TEST_KEY.clone();
        HmacSha256Identifier identifier = new HmacSha256Identifier(mutableKey);
        mutableKey[0] = 'x';

        assertEquals("hBJA0qW2ZUs64h_ESZ23t4Zwd83WfD4WzvH5hD4n0fo",
                identifier.identify("alice@example.com").value());
    }

    @Test
    void hmacIdentifierCannotBeConstructedThroughPublicApi() {
        assertEquals(0, Arrays.stream(HmacIdentifier.class.getConstructors()).count());
    }
}
