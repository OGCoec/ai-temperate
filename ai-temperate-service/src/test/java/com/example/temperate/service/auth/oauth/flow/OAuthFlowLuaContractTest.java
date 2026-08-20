package com.example.temperate.service.auth.oauth.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 验证 OAuth state、原生 nonce 和短时 Flow 的 Redis Lua 原子消费合同。
 */
class OAuthFlowLuaContractTest {

    @Test
    void browserStateMustBeDeletedInSameScriptThatReturnsIt() throws Exception {
        String lua = read("consume_oauth_authorization_state.lua");
        assertThat(lua).contains("redis.call('unlink', keys[1])");
        assertThat(lua).contains("bindinghash");
        assertThat(lua).contains("flowid");
        assertThat(lua).contains("codeverifier");
    }

    @Test
    void nativeNonceMustBeOneTimeAndBoundToDevice() throws Exception {
        String lua = read("consume_oauth_native_nonce.lua");
        assertThat(lua).contains("devicehash");
        assertThat(lua).contains("nativeconsumed");
        assertThat(lua).contains("noncehash");
        assertThat(lua).contains("hset");
    }

    @Test
    void completionClaimMustRejectDuplicateSessionIssuance() throws Exception {
        String lua = read("claim_oauth_completion.lua");
        assertThat(lua).contains("ready_to_complete");
        assertThat(lua).contains("completionclaim");
        assertThat(lua).contains("devicehash");
        assertThat(lua).contains("iphash");
    }

    @Test
    void providerCompletionMustRenewIdleExpiryWithoutExceedingAbsoluteExpiry()
            throws Exception {
        String lua = read("complete_oauth_provider.lua");
        assertThat(lua).contains("math.min", "absoluteexpiresat", "pexpire");
    }

    @Test
    void oauthPhoneRiskMustUseFiveMinuteWindowAndTwoBlockKeys() throws Exception {
        String send = read("check_oauth_phone_send_risk.lua");
        String conflict = read("record_oauth_phone_conflict.lua");
        assertThat(send).contains("requestcount", "lastrequestedat", "keys[2]", "keys[3]");
        assertThat(conflict).contains("conflictcount", "keys[2]", "keys[3]");
    }

    private static String read(String name) throws Exception {
        return Files.readString(
                Path.of("src", "main", "resources", "lua", "auth-oauth", name),
                StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }
}
