package com.example.temperate.service.auth.passwordreset.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 验证找回密码流程的受保护访问材料和 Lua 脚本保留全局设备封禁总闸门。
 */
class PasswordResetGlobalDeviceBlockContractTest {

    @Test
    void protectedAccessCarriesGlobalDeviceHash() {
        assertThat(Arrays.stream(ProtectedPasswordResetAccess.class.getRecordComponents())
                        .map(RecordComponent::getName))
                .containsExactly(
                        "flowId",
                        "challengeId",
                        "deviceHash",
                        "globalDeviceHash",
                        "codeId",
                        "targetHash");
    }

    @Test
    void flowStoreRequiresLocalAndGlobalDeviceHashesForBlockLookup() throws Exception {
        assertThat(PasswordResetFlowStore.class.getMethod(
                        "isBlocked", HmacIdentifier.class, HmacIdentifier.class))
                .isNotNull();
        assertThatThrownBy(() -> PasswordResetFlowStore.class.getMethod(
                        "isBlocked", HmacIdentifier.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void luaScriptsCheckAndWriteGlobalDeviceBlockKey() throws Exception {
        Path lua = findProjectRoot().resolve(
                "ai-temperate-service/src/main/resources/lua/auth-password-reset");
        String issue = Files.readString(lua.resolve("issue_password_reset_code.lua"));
        String verify = Files.readString(lua.resolve("verify_password_reset_code.lua"));

        assertThat(issue)
                .contains("KEYS[6]")
                .contains("redis.call('PSETEX', KEYS[6], blockMillis, '1')");
        assertThat(verify)
                .contains("KEYS[6]")
                .contains("redis.call('PSETEX', KEYS[6], blockMillis, '1')")
                .contains("redis.call('HSET', KEYS[5]");
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-service"))
                    && Files.isDirectory(current.resolve("ai-temperate-web"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root.");
    }
}
