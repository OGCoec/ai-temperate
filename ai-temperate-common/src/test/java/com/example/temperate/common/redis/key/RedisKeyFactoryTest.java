package com.example.temperate.common.redis.key;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证 Redis Key 的命名空间、敏感标识保护、长度边界和规范化格式。
 */
final class RedisKeyFactoryTest {

    private static final String[][] REGISTRATION_KEY_CASES = {
            {"registrationFlowKey", "flow"},
            {"registrationEmailCodeKey", "email-code"},
            {"registrationPhoneCodeKey", "phone-code"},
            {"registrationConflictKey", "conflict"},
            {"registrationBlockKey", "block"},
            {"registrationChallengeKey", "challenge"},
            {"registrationSendRiskKey", "send-risk"},
            {"registrationVerifyRiskKey", "verify-risk"}
    };

    private static final String[][] AUTH_SESSION_KEY_CASES = {
            {"loginFailureKey", "limit", "login-failure"},
            {"loginPasswordFailureKey", "limit", "password-failure"},
            {"loginCodeFailureKey", "limit", "code-failure"},
            {"loginBlockKey", "limit", "login-block"},
            {"loginFlowKey", "login", "flow"},
            {"loginEmailCodeKey", "login", "email-code"},
            {"loginPhoneCodeKey", "login", "phone-code"},
            {"loginChallengeKey", "login", "challenge"},
            {"passwordResetFlowKey", "password-reset", "flow"},
            {"passwordResetForgetKey", "password-reset", "forget"},
            {"passwordResetEmailCodeKey", "password-reset", "email-code"},
            {"passwordResetPhoneCodeKey", "password-reset", "phone-code"},
            {"passwordResetSendRiskKey", "password-reset", "send-risk"},
            {"passwordResetVerifyRiskKey", "password-reset", "verify-risk"},
            {"passwordResetBlockKey", "password-reset", "block"},
            {"passwordResetTargetSendKey", "password-reset", "target-send"}
    };

    @Test
    void createsKeyUsingFixedProjectPrefixAndOrderedNamespaceSegments() {
        RedisKeyFactory factory = new RedisKeyFactory("prod");

        assertEquals("ait:prod:auth:uli:v1:id:10001",
                factory.idKey("auth", "uli", "v1", 10001L));
    }

    @Test
    void createsFixedWidthBucketKeyWithoutFreeFormTypeOrIdentifier() {
        RedisKeyFactory factory = new RedisKeyFactory("test");

        assertEquals("ait:test:bloom:uli:v1:bucket:0007",
                factory.bucketKey("bloom", "uli", "v1", 7));
    }

    @Test
    void createsIdentityPresenceBloomLifecycleKeys() {
        RedisKeyFactory factory = new RedisKeyFactory("test");

        assertEquals(
                "ait:test:bloom:uli-presence:v1:control:state",
                factory.identityPresenceBloomControlKey());
        assertEquals(
                "ait:test:bloom:uli-presence:v1:build-lock:lease",
                factory.identityPresenceBloomBuildLockKey());
        assertEquals(
                "ait:test:bloom:uli-presence:v1-g123:meta:config",
                factory.identityPresenceBloomMetaKey("v1-g123"));
        assertEquals(
                "ait:test:bloom:uli-presence:v1-g123:receipt:0007",
                factory.identityPresenceBloomReceiptKey("v1-g123", 7));
    }

    @Test
    void rejectsInvalidNamespaceSegments() {
        assertThrows(IllegalArgumentException.class, () -> new RedisKeyFactory(null));
        assertThrows(IllegalArgumentException.class, () -> new RedisKeyFactory("Prod"));
        assertThrows(IllegalArgumentException.class, () -> new RedisKeyFactory("生产"));

        RedisKeyFactory factory = new RedisKeyFactory("prod");
        assertThrows(IllegalArgumentException.class,
                () -> factory.idKey("Auth", "uli", "v1", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> factory.idKey("auth", "user_profile", "v1", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> factory.idKey("auth", "uli", "v1:beta", 1L));
    }

    @Test
    void rejectsInvalidTypedIdentifiers() {
        RedisKeyFactory factory = new RedisKeyFactory("prod");

        assertThrows(IllegalArgumentException.class,
                () -> factory.idKey("auth", "uli", "v1", 0L));
        assertThrows(IllegalArgumentException.class,
                () -> factory.idKey("auth", "uli", "v1", -1L));
        assertThrows(IllegalArgumentException.class,
                () -> factory.bucketKey("bloom", "uli", "v1", -1));
        assertThrows(IllegalArgumentException.class,
                () -> factory.bucketKey("bloom", "uli", "v1", 10_000));
    }

    @Test
    void emailAndPhoneKeysOnlyAcceptIdentifierProducedByHmacComponent() throws Exception {
        RedisKeyFactory factory = new RedisKeyFactory("prod");
        HmacIdentifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                .identify("alice@example.com");

        assertEquals("ait:prod:auth:uli:v1:email:" + hmac.value(),
                factory.emailKey("auth", "uli", "v1", hmac));
        assertEquals("ait:prod:auth:uli:v1:phone:" + hmac.value(),
                factory.phoneKey("auth", "uli", "v1", hmac));
        assertThrows(IllegalArgumentException.class,
                () -> factory.phoneKey("auth", "uli", "v1", null));

        assertThrows(NoSuchMethodException.class,
                () -> RedisKeyFactory.class.getMethod(
                        "emailKey", String.class, String.class, String.class, String.class));
        assertThrows(NoSuchMethodException.class,
                () -> RedisKeyFactory.class.getMethod(
                        "phoneKey", String.class, String.class, String.class, String.class));
    }

    @Test
    void exposesNoGenericTypeOrMobileBypassApi() {
        assertThrows(NoSuchMethodException.class,
                () -> RedisKeyFactory.class.getMethod(
                        "create", String.class, String.class, String.class,
                        String.class, String.class));
        assertFalse(Arrays.stream(RedisKeyFactory.class.getMethods())
                .anyMatch(method -> method.getName().equals("mobileKey")));
    }

    @Test
    void createsRegistrationKeysWithFixedNamespaceAndHmacIdentifier() throws Exception {
        RedisKeyFactory factory = new RedisKeyFactory("prod");
        HmacIdentifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                .identify("registration-ticket");

        for (String[] keyCase : REGISTRATION_KEY_CASES) {
            Method method = assertDoesNotThrow(
                    () -> RedisKeyFactory.class.getMethod(keyCase[0], HmacIdentifier.class));

            assertEquals("ait:prod:auth:register:v2:" + keyCase[1] + ":" + hmac.value(),
                    method.invoke(factory, hmac));
        }
    }

    @Test
    void registrationKeysExposeNoRawStringOverload() {
        for (String[] keyCase : REGISTRATION_KEY_CASES) {
            assertThrows(NoSuchMethodException.class,
                    () -> RedisKeyFactory.class.getMethod(keyCase[0], String.class));
        }
    }

    @Test
    void registrationKeysRejectMissingHmacIdentifier() throws Exception {
        RedisKeyFactory factory = new RedisKeyFactory("prod");

        for (String[] keyCase : REGISTRATION_KEY_CASES) {
            Method method = assertDoesNotThrow(
                    () -> RedisKeyFactory.class.getMethod(keyCase[0], HmacIdentifier.class));
            InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(factory, (Object) null));

            assertTrue(exception.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    void createsTypedLoginAndSessionKeysWithoutRawTokenOverloads() throws Exception {
        RedisKeyFactory factory = new RedisKeyFactory("test");
        HmacIdentifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                .identify("opaque-auth-session-identifier");

        for (String[] keyCase : AUTH_SESSION_KEY_CASES) {
            Method method = RedisKeyFactory.class.getMethod(keyCase[0], HmacIdentifier.class);
            assertEquals(
                    "ait:test:auth:" + keyCase[1] + ":v2:" + keyCase[2] + ":" + hmac.value(),
                    method.invoke(factory, hmac));
            assertThrows(NoSuchMethodException.class,
                    () -> RedisKeyFactory.class.getMethod(keyCase[0], String.class));
        }

        Method userIndex = RedisKeyFactory.class.getMethod("sessionUserIndexKey", long.class);
        assertEquals("ait:test:auth:session:v4:user-rts:10001",
                userIndex.invoke(factory, 10001L));

        assertEquals("ait:test:auth:session:v4:rt:" + hmac.value(),
                factory.sessionRefreshTokenKey(hmac));
        assertEquals("ait:test:auth:session:v3:user-rts:10001",
                factory.legacySessionUserIndexKey(10001L));
        assertEquals("ait:test:auth:session:v3:rt:" + hmac.value(),
                factory.legacySessionRefreshTokenKey(hmac));
        assertThrows(NoSuchMethodException.class,
                () -> RedisKeyFactory.class.getMethod("sessionRefreshTokenKey", String.class));
    }

    @Test
    void createsGlobalDeviceBlockKeyWithoutRawDeviceOverload() throws Exception {
        RedisKeyFactory factory = new RedisKeyFactory("test");
        HmacIdentifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                .identify("device-installation-id");

        assertEquals("ait:test:auth:device:v2:block:" + hmac.value(),
                factory.globalDeviceBlockKey(hmac));
        assertThrows(NoSuchMethodException.class,
                () -> RedisKeyFactory.class.getMethod("globalDeviceBlockKey", String.class));
    }

    @Test
    void createsOnlyV4PreAuthKeysWithoutIndependentRiskStateKeys()
            throws Exception {
        RedisKeyFactory factory = new RedisKeyFactory("prod");
        HmacIdentifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8))
                .identify("preauth-token");

        assertEquals(
                "ait:prod:risk:preauth-user:v4:token:" + hmac.value(),
                factory.userPreAuthKey(hmac));
        assertEquals(
                "ait:prod:risk:preauth-admin:v4:token:" + hmac.value(),
                factory.adminPreAuthKey(hmac));
        assertThrows(NoSuchMethodException.class, () ->
                RedisKeyFactory.class.getMethod(
                        "userRiskChallengeKey",
                        HmacIdentifier.class));
        assertThrows(NoSuchMethodException.class, () ->
                RedisKeyFactory.class.getMethod(
                        "userImpossibleTravelEventsKey",
                        HmacIdentifier.class));
    }

    @Test
    void createsOnlyV3IpIntelligenceKeys() {
        RedisKeyFactory factory = new RedisKeyFactory("prod");
        HmacIdentifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                .identify("risk-ip-v3");

        assertEquals(
                "ait:prod:risk:ipintel:v3:ip:" + hmac.value(),
                factory.ipIntelligenceCacheKey(hmac));
        assertEquals(
                "ait:prod:risk:ipintel:v3:single-flight:" + hmac.value(),
                factory.ipIntelligenceSingleFlightKey(hmac));
    }

    @Test
    void createsFixedAiModelEnabledSnapshotKey() {
        RedisKeyFactory factory = new RedisKeyFactory("prod");

        assertEquals("ait:prod:ai:model:v5:enabled", factory.aiModelEnabledSnapshotKey());
    }

    @Test
    void createsUserProfileKeyOnlyFromEncryptedIdentifier() throws Exception {
        RedisKeyFactory factory = new RedisKeyFactory("prod");
        EncryptedRedisId encryptedId =
                new EncryptedRedisId("I11B5RV16PBmGzFJEwJf3g");

        assertEquals(
                "ait:prod:user:profile:v1:enc-id:I11B5RV16PBmGzFJEwJf3g",
                factory.userProfileKey(encryptedId));
        assertThrows(NoSuchMethodException.class,
                () -> RedisKeyFactory.class.getMethod("userProfileKey", long.class));
        assertThrows(NoSuchMethodException.class,
                () -> RedisKeyFactory.class.getMethod("userProfileKey", String.class));
    }

    @Test
    void createsMailInspectionKeysWithoutRawJobOrClientRequestIds() {
        RedisKeyFactory factory = new RedisKeyFactory("test");
        HmacSha256Identifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        HmacIdentifier jobHash = hmac.identify("mail-job-id:v2",
                "abcdefghijklmnopqrstuv".getBytes(StandardCharsets.UTF_8));
        HmacIdentifier requestHash = hmac.identify("mail-client-request-id:v2",
                "request-1".getBytes(StandardCharsets.UTF_8));

        assertEquals("ait:test:admin-mail:job:v2:meta:" + jobHash.value(),
                factory.adminMailInspectionJobMetaKey(jobHash));
        assertEquals("ait:test:admin-mail:job:v2:counts:" + jobHash.value(),
                factory.adminMailInspectionJobCountsKey(jobHash));
        assertEquals("ait:test:admin-mail:job:v2:results:" + jobHash.value() + ":0007",
                factory.adminMailInspectionJobResultBucketKey(jobHash, 7));
        assertEquals("ait:test:admin-mail:job:v2:idempotency:" + requestHash.value(),
                factory.adminMailInspectionJobIdempotencyKey(requestHash));
        assertEquals("ait:test:admin-mail:job:v2:active:openai-status",
                factory.adminMailInspectionJobActiveKey("openai-status"));
        assertEquals("ait:test:admin-mail:job:v2:acceptance:openai-status",
                factory.adminMailInspectionJobAcceptanceKey("openai-status"));
        assertEquals("ait:test:admin-mail:job:v2:revision:" + jobHash.value(),
                factory.adminMailInspectionJobRevisionKey(jobHash));
        assertEquals("ait:test:admin-mail:job:v2:events",
                factory.adminMailInspectionJobEventsChannel());
    }

    @Test
    void rejectsInvalidMailInspectionBucketAndTypeSegments() {
        RedisKeyFactory factory = new RedisKeyFactory("prod");
        HmacIdentifier jobHash = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))
                .identify("mail-job-id:v2", "job".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class,
                () -> factory.adminMailInspectionJobResultBucketKey(jobHash, -1));
        assertThrows(IllegalArgumentException.class,
                () -> factory.adminMailInspectionJobResultBucketKey(jobHash, 10_000));
        assertThrows(IllegalArgumentException.class,
                () -> factory.adminMailInspectionJobActiveKey("OPENAI_STATUS"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.adminMailInspectionJobAcceptanceKey("openai/status"));
    }

    @Test
    void exposesTypedSessionPrefixesForBoundedLuaIndexTraversal() {
        RedisKeyFactory factory = new RedisKeyFactory("test");

        assertEquals("ait:test:auth:session:v4:rt:", factory.sessionRefreshTokenKeyPrefix());
        assertEquals("ait:test:auth:session:v4:user-rts:",
                factory.sessionUserIndexKeyPrefix());
        assertEquals("ait:test:auth:session:v3:rt:",
                factory.legacySessionRefreshTokenKeyPrefix());
        assertEquals("ait:test:auth:session:v3:user-rts:",
                factory.legacySessionUserIndexKeyPrefix());
        assertTrue(factory.sessionRefreshTokenKeyPrefix().getBytes(StandardCharsets.UTF_8).length
                < RedisKeyFactory.TARGET_MAX_BYTES);
    }

    @Test
    void acceptsAboveTargetLengthWithoutWarningWithinNormalLimit() {
        AtomicReference<RedisKeyFactory.KeyLengthWarning> warning = new AtomicReference<>();
        RedisKeyFactory factory = new RedisKeyFactory("prod", warning::set);

        String key = factory.idKey("auth", "a".repeat(80), "v1", 1L);

        int byteLength = key.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(byteLength > RedisKeyFactory.TARGET_MAX_BYTES);
        assertTrue(byteLength <= RedisKeyFactory.NORMAL_MAX_BYTES);
        assertNull(warning.get());
    }

    @Test
    void warnsWithoutSensitiveIdentifierWhenKeyExceedsNormalLimit() {
        AtomicReference<RedisKeyFactory.KeyLengthWarning> warning = new AtomicReference<>();
        RedisKeyFactory factory = new RedisKeyFactory("prod", warning::set);

        String key = factory.idKey("auth", "a".repeat(110), "v1", 1L);

        int byteLength = key.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(byteLength > RedisKeyFactory.NORMAL_MAX_BYTES);
        assertEquals(byteLength, warning.get().byteLength());
        assertEquals("prod", warning.get().environment());
        assertEquals("auth", warning.get().domain());
        assertEquals("a".repeat(110), warning.get().object());
        assertEquals("v1", warning.get().version());
        assertEquals(RedisKeyFactory.IdentifierType.ID, warning.get().type());
        assertFalse(warning.get().toString().contains("1".repeat(20)));
    }

    @Test
    void rejectsKeysLongerThanAbsoluteLimit() {
        RedisKeyFactory factory = new RedisKeyFactory("prod");

        assertThrows(IllegalArgumentException.class,
                () -> factory.idKey("auth", "a".repeat(300), "v1", 1L));
    }
}
