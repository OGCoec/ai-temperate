package com.example.temperate.service.user.aiinference.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定 API Key、账号、全局三个 ZSET 的单 Lua 原子顺序、续租完整性和释放路径，使 H5、Android 与 API Key 共享后两层物理并发池。
 */
final class AiInferenceConcurrencyLuaContractTest {

    private static final Path SCRIPT_ROOT =
            Path.of("src/main/resources/lua/ai-conversation");

    @Test
    void acquireCleansAndChecksKeyAccountGlobalInFixedPriority() throws IOException {
        String script = read("acquire_api_key_concurrency.lua");

        int keyCheck = script.indexOf("ZCARD', KEYS[1]");
        int accountCheck = script.indexOf("ZCARD', KEYS[2]");
        int globalCheck = script.indexOf("ZCARD', KEYS[3]");
        assertThat(script).contains("ZREMRANGEBYSCORE").contains("return 2")
                .contains("return 3").contains("return 4");
        assertThat(keyCheck).isGreaterThanOrEqualTo(0);
        assertThat(accountCheck).isGreaterThan(keyCheck);
        assertThat(globalCheck).isGreaterThan(accountCheck);
    }

    @Test
    void renewPreflightsAllThreeSetsBeforeExtendingAnyLease() throws IOException {
        String script = read("renew_api_key_concurrency.lua");

        assertThat(script.indexOf("ZSCORE")).isLessThan(script.indexOf("ZADD"));
        assertThat(script).contains("for keyIndex = 1, 3 do").contains("return 0");
    }

    @Test
    void releaseRemovesSameOwnerFromAllThreeSets() throws IOException {
        assertThat(read("release_api_key_concurrency.lua"))
                .contains("for keyIndex = 1, 3 do")
                .contains("ZREM");
    }

    private static String read(String name) throws IOException {
        return Files.readString(SCRIPT_ROOT.resolve(name), StandardCharsets.UTF_8);
    }
}
