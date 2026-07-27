package com.example.temperate.service.auth.session.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * 验证访问令牌与刷新会话存储之间的模块边界和认证材料保护契约。
 */
class AuthTokenAndSessionStoreContractTest {

    @Test
    void tokenDomainUsesAnInterfaceAndFinalServiceImplementation() {
        Class<?> contract = load("com.example.temperate.service.auth.session.token.service.AuthTokenService");
        Class<?> implementation = load(
                "com.example.temperate.service.auth.session.token.service.impl.AuthTokenServiceImpl");

        assertThat(contract.isInterface()).isTrue();
        assertThat(contract.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder(
                        "issueAccessToken",
                        "verifyAccessToken",
                        "newRefreshToken",
                        "newFlowToken",
                        "newCsrfToken");
        assertThat(implementation.getInterfaces()).contains(contract);
        assertThat(Modifier.isFinal(implementation.getModifiers())).isTrue();
        assertThat(implementation.getAnnotation(Service.class)).isNotNull();
    }

    @Test
    void refreshSessionStoreUsesAnInterfaceAndRedisImplementation() throws Exception {
        Class<?> contract = load(
                "com.example.temperate.service.auth.session.refresh.store.RefreshSessionStore");
        Class<?> implementation = load(
                "com.example.temperate.service.auth.session.refresh.store.impl.RedisRefreshSessionStore");

        assertThat(contract.isInterface()).isTrue();
        assertThat(contract.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder(
                        "create",
                        "validateAndRenew",
                        "validateAndRenewWithPreAuth",
                        "bootstrapAndRenew",
                        "bootstrapAndRenewWithPreAuth",
                        "revoke",
                        "revokeAllForUser");
        assertThat(implementation.getInterfaces()).contains(contract);
        assertThat(Modifier.isFinal(implementation.getModifiers())).isTrue();
        assertThat(implementation.getAnnotation(Component.class)).isNotNull();
        assertThat(implementation.getConstructor(
                        StringRedisTemplate.class,
                        RedisKeyFactory.class,
                        PublicIdCodec.class,
                        Duration.class,
                        int.class,
                        int.class))
                .isNotNull();
        var codecField = implementation.getDeclaredField("publicIdCodec");
        assertThat(codecField.getType()).isEqualTo(PublicIdCodec.class);
        assertThat(Modifier.isFinal(codecField.getModifiers())).isTrue();

        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/auth/session/refresh/store/impl/"
                        + "RedisRefreshSessionStore.java"));
        assertThat(source).doesNotContain("new PublicIdCodec(");
        assertThat(source).contains("executePipelined", "unlink");
        assertThat(source).doesNotContain("REVOKE_ALL_SCRIPT", "MSET");

        var validate = contract.getMethod(
                "validateAndRenew",
                HmacIdentifier.class,
                HmacIdentifier.class,
                HmacIdentifier.class);
        assertThat(validate.getReturnType().getSimpleName())
                .isEqualTo("RefreshSessionValidation");
        Class<?> newSession = load(
                "com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession");
        assertThat(newSession.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "userId",
                        "publicId",
                        "refreshTokenHash",
                        "deviceHash",
                        "csrfHash",
                        "email",
                        "phone");
    }

    @Test
    void refreshSessionStoreProvidesEveryAtomicLuaOperation() {
        Path luaDirectory = Path.of("src/main/resources/lua/auth-session");
        Set<String> required = Set.of(
                "create_refresh_session.lua",
                "validate_refresh_session.lua",
                "validate_refresh_session_with_preauth.lua",
                "revoke_refresh_session.lua",
                "update_refresh_session_csrf.lua",
                "update_refresh_session_csrf_with_preauth.lua");

        for (String fileName : required) {
            assertThat(Files.isRegularFile(luaDirectory.resolve(fileName)))
                    .as("missing atomic script %s", fileName)
                    .isTrue();
        }
    }

    private static Class<?> load(String className) {
        final Class<?>[] result = new Class<?>[1];
        assertThatCode(() -> result[0] = Class.forName(className))
                .as("expected class %s", className)
                .doesNotThrowAnyException();
        return result[0];
    }
}
