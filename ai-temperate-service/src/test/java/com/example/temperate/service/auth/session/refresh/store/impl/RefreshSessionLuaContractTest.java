package com.example.temperate.service.auth.session.refresh.store.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 验证刷新会话 Lua 脚本的资源引用、状态码和原子操作契约。
 */
class RefreshSessionLuaContractTest {

    private static final Path LUA = Path.of("src/main/resources/lua/auth-session");

    @Test
    void createStoresSixFieldsAndSetsAlignedRedisServerExpiry() throws IOException {
        String script = script("create_refresh_session.lua");

        assertThat(script).contains(
                "redis.call('time')",
                "redis.call('hlen'",
                "'userid'",
                "'publicid'",
                "'csrfhash'",
                "'email'",
                "'phone'",
                "'devicehash'",
                "hpexpireat",
                "pexpireat");
        assertThat(script).contains("hset', keys[2], tokenhash, keys[1]");
        assertThat(script).doesNotContain(
                "familyhash", "sessionid", "passwordversion", "membershiptier");
    }

    @Test
    void validateRequiresExistingIndexFieldBeforeRenewingAllThreeTtls() throws IOException {
        String script = script("validate_refresh_session.lua");

        assertThat(script).contains(
                "hmget",
                "hexists",
                "redis.call('time')",
                "hpexpireat",
                "pexpireat");
        assertThat(script).doesNotContain("redis.call('hset'", "scan", "keys");
    }

    @Test
    void bootstrapChangesOnlyCsrfAndRenewsTheSameFixedRt() throws IOException {
        String script = script("update_refresh_session_csrf.lua");

        assertThat(script).contains(
                "hexists",
                "redis.call('hset', keys[1], 'csrfhash'",
                "hpexpireat",
                "pexpireat");
        assertThat(script).doesNotContain("newrefreshtoken", "familyhash", "used");
    }

    @Test
    void currentRevokeUsesBoundedFieldTtlRecalculation() throws IOException {
        String script = script("revoke_refresh_session.lua");

        assertThat(script).contains(
                "absolute revokebound".replace(" ", ""),
                "hdel",
                "hkeys",
                "hpttl",
                "unlink",
                "pexpire");
        assertThat(script).doesNotContain("redis.call('keys'", "scan");
    }

    private static String script(String name) throws IOException {
        return Files.readString(LUA.resolve(name))
                .toLowerCase(Locale.ROOT)
                .replace("_", "");
    }
}
