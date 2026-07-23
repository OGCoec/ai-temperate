package com.example.temperate.service.registration.flow.store.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 验证注册 Lua 脚本均通过原子执行维护状态与风险控制约束的契约测试。
 */
class RegistrationLuaContractTest {

    private static final String SCRIPT_ROOT = "lua/registration/";
    private static final List<String> POST_CREATION_SCRIPTS = List.of(
            "get_registration_flow.lua",
            "issue_registration_code.lua",
            "claim_registration_code_delivery_attempt.lua",
            "release_registration_code_delivery_for_retry.lua",
            "mark_registration_code_delivery_success.lua",
            "mark_registration_code_delivery_accepted.lua",
            "mark_registration_code_delivery_unknown.lua",
            "compensate_registration_code_delivery_failure.lua",
            "verify_registration_code.lua",
            "mark_registration_human_verified.lua",
            "claim_registration_completion.lua",
            "release_registration_completion.lua",
            "delete_registration_flow.lua");
    private static final Pattern FLOW_TTL_RENEWAL = Pattern.compile(
            "redis\\.call\\(['\"]P?EXPIRE['\"],\\s*KEYS\\[1\\]",
            Pattern.CASE_INSENSITIVE);

    @Test
    void createStoresCompleteSchemaWithOneFixedTenMinuteTtl() {
        String script = script("create_registration_flow.lua");

        assertThat(script)
                .contains("local IDLE_TTL_MILLIS = 600000")
                .contains("local ABSOLUTE_TTL_MILLIS = 1800000")
                .contains("'schemaVersion'")
                .contains("'email'")
                .contains("'phone'")
                .contains("'deviceHash'")
                .contains("'ipHash'")
                .contains("'humanVerified'")
                .contains("'emailVerified'")
                .contains("'phoneVerified'")
                .contains("'createdAt'")
                .contains("'expiresAt'")
                .contains("'absoluteExpiresAt'")
                .contains("'flowCsrfHash'")
                .contains("'challengeHash'")
                .contains("redis.call('PEXPIRE', KEYS[1], IDLE_TTL_MILLIS)")
                .contains("redis.call('SET', KEYS[2], ARGV[14], 'PX', IDLE_TTL_MILLIS)");
    }

    @Test
    void sixthConflictInsideFiveMinutesCreatesLocalAndGlobalBlocksAtomically() {
        String script = script("record_registration_conflict.lua");

        assertThat(script)
                .contains("local WINDOW_MILLIS = 300000")
                .contains("local BLOCK_SECONDS = 7200")
                .contains("local ALLOWED_FAILURES = 5")
                .contains("local windowMillis = tonumber(ARGV[2])")
                .contains("local blockSeconds = tonumber(ARGV[3])")
                .contains("windowMillis ~= WINDOW_MILLIS")
                .contains("blockSeconds ~= BLOCK_SECONDS")
                .contains("redis.call('HINCRBY', KEYS[1], 'total', 1)")
                .contains("count > ALLOWED_FAILURES")
                .contains("redis.call('SET', KEYS[2], '1', 'EX', blockSeconds)")
                .contains("redis.call('SET', KEYS[3], '1', 'EX', blockSeconds)");
    }

    @Test
    void codeIssueUsesCooldownSendCapAndRemainingFlowTtl() {
        String script = script("issue_registration_code.lua");

        assertThat(script)
                .contains("local COOLDOWN_MILLIS = 60000")
                .contains("local ALLOWED_SENDS = 5")
                .contains("local CODE_MAX_TTL_MILLIS = 300000")
                .contains("local codeMaxTtlMillis = tonumber(ARGV[8])")
                .contains("local cooldownMillis = tonumber(ARGV[9])")
                .contains("local sendLimit = tonumber(ARGV[10])")
                .contains("local windowMillis = tonumber(ARGV[14])")
                .contains("local blockSeconds = tonumber(ARGV[15])")
                .contains("'sendOperationId', ARGV[7]")
                .contains("'deliveryStatus', 'PENDING'")
                .contains("redis.call('PTTL', KEYS[1])")
                .contains("math.min(codeMaxTtlMillis, flowPttl)")
                .contains("redis.call('PEXPIRE', KEYS[2], codeTtl)")
                .contains("redis.call('SET', KEYS[4], '1', 'EX', blockSeconds)")
                .contains("redis.call('SET', KEYS[5], '1', 'EX', blockSeconds)")
                .contains("humanVerified");
    }

    @Test
    void deliveryCallbacksAreOperationBoundIdempotentAndNeverRenewFlowTtl() {
        String success = script("mark_registration_code_delivery_success.lua");
        String accepted = script("mark_registration_code_delivery_accepted.lua");
        String unknown = script("mark_registration_code_delivery_unknown.lua");
        String failure = script("compensate_registration_code_delivery_failure.lua");
        String claim = script("claim_registration_code_delivery_attempt.lua");
        String release = script("release_registration_code_delivery_for_retry.lua");

        assertThat(claim)
                .contains("values[1] ~= ARGV[5]")
                .contains("values[2] == 'DELIVERING' and values[3] == ARGV[7]")
                .contains("'deliveryStatus', 'DELIVERING'")
                .contains("'activeMessageId', ARGV[7]");
        assertThat(release)
                .contains("values[1] ~= ARGV[5] or values[2] ~= 'DELIVERING' or values[3] ~= ARGV[6]")
                .contains("redis.call('HSET', KEYS[2], 'deliveryStatus', 'PENDING')")
                .contains("redis.call('HDEL', KEYS[2], 'activeMessageId')");

        assertThat(success)
                .contains("operationId ~= ARGV[5]")
                .contains("deliveryStatus ~= 'PENDING' and deliveryStatus ~= 'DELIVERING'")
                .contains("'deliveryStatus', 'SUCCESS'")
                .contains("redis.call('HDEL', KEYS[2], 'activeMessageId')")
                .contains("redis.call('SET', KEYS[4], '1', 'EX', blockSeconds)")
                .contains("redis.call('SET', KEYS[5], '1', 'EX', blockSeconds)");
        assertThat(accepted)
                .contains("'deliveryStatus', 'SUCCESS'")
                .contains("'providerMessageId', ARGV[9]")
                .contains("'providerStatus', ARGV[10]")
                .contains("deliveryStatus == 'SUCCESS'");
        assertThat(unknown)
                .contains("'deliveryStatus', 'UNKNOWN'")
                .contains("'unknownReason', ARGV[6]")
                .contains("deliveryStatus == 'UNKNOWN'");
        assertThat(failure)
                .contains("operationId ~= ARGV[5]")
                .contains("deliveryStatus ~= 'PENDING' and deliveryStatus ~= 'DELIVERING'")
                .contains("redis.call('DEL', KEYS[2])")
                .contains("redis.call('HDEL', KEYS[3], ARGV[8])")
                .doesNotContain("PEXPIRE', KEYS[1]", "EXPIRE', KEYS[1]");
    }

    @Test
    void codeVerificationDeletesAtFiveFailuresAndConsumesAValidCode() {
        String script = script("verify_registration_code.lua");

        assertThat(script)
                .contains("local ATTEMPT_LIMIT = 5")
                .contains("local attemptLimit = tonumber(ARGV[7])")
                .contains("attemptLimit ~= ATTEMPT_LIMIT")
                .contains("redis.call('HINCRBY', KEYS[2], 'attempts', 1)")
                .contains("attempts >= attemptLimit")
                .contains("stored[2] ~= 'SUCCESS' and stored[2] ~= 'UNKNOWN'")
                .contains("redis.call('DEL', KEYS[2])")
                .contains("redis.call('HSET', KEYS[1], verifiedField, '1')");
        assertThat(count(script, "redis.call('DEL', KEYS[2])")).isEqualTo(2);
    }

    @Test
    void turnstileMarkConsumesTheBoundChallengeOnlyOnce() {
        String script = script("mark_registration_human_verified.lua");

        assertThat(script)
                .contains("redis.call('GET', KEYS[2])")
                .contains("redis.call('DEL', KEYS[2])")
                .contains("redis.call('HSET', KEYS[1], 'humanVerified', '1')")
                .contains("humanVerified == '1'");
    }

    @Test
    void completionClaimRequiresAllVerificationAndReleaseMatchesClaim() {
        String claim = script("claim_registration_completion.lua");
        String release = script("release_registration_completion.lua");

        assertThat(claim)
                .contains("humanVerified ~= '1'")
                .contains("emailVerified ~= '1'")
                .contains("phoneVerified ~= '1'")
                .contains("redis.call('HSET', KEYS[1], 'completionClaim', ARGV[6])");
        assertThat(release)
                .contains("storedClaim ~= ARGV[5]")
                .contains("redis.call('HDEL', KEYS[1], 'completionClaim')");
    }

    @Test
    void deletionUsesOneBatchUnlinkForAllEphemeralKeys() {
        String script = script("delete_registration_flow.lua");

        assertThat(script)
                .contains("redis.call('UNLINK', KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6])");
        assertThat(count(script, "redis.call('UNLINK'")).isEqualTo(1);
    }

    @Test
    void noPostCreationScriptRenewsTheFlowTtl() {
        for (String scriptName : POST_CREATION_SCRIPTS) {
            assertThat(FLOW_TTL_RENEWAL.matcher(script(scriptName)).find())
                    .as("%s must preserve the fixed flow expiry", scriptName)
                    .isFalse();
        }
    }

    private static String script(String name) {
        String resource = SCRIPT_ROOT + name;
        try (InputStream input = RegistrationLuaContractTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Unable to read " + resource, exception);
        }
    }

    private static int count(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
