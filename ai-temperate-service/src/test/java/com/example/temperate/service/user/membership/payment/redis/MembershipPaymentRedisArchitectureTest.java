package com.example.temperate.service.user.membership.payment.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 该静态测试是来约束会员支付 Redis 实现只使用 Hash、ZSet、Pipeline 和 Lua，不引入 Redis Stream 或非原子领取。
 */
final class MembershipPaymentRedisArchitectureTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void queueScriptsUseLeaseScoresAndExactMarkerDeletion() throws IOException {
        String enqueue = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/enqueue_callback.lua");
        String claim = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/callback_claim.lua");
        String recover = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/callback_recover.lua");
        String complete = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/callback_complete.lua");

        assertThat(enqueue)
                .contains("order_idempotency_key")
                .contains("provider_trade_idempotency_key")
                .contains("redis.call('GET', order_idempotency_key)")
                .contains("redis.call('GET', provider_trade_idempotency_key)")
                .doesNotContain("redis.call('SET', marker_key, existing");
        assertThat(claim)
                .contains("ZRANGE")
                .contains("ZREM")
                .contains("ZADD");
        assertThat(recover)
                .contains("ZRANGEBYSCORE")
                .contains("LIMIT");
        assertThat(complete)
                .contains("ZSCORE")
                .contains("expected_score")
                .contains("redis.call('GET', marker_key) == callback_id")
                .contains("redis.call('HGET', provider_result_key, 'callbackId') == callback_id")
                .contains("remove_provider_result == '1'")
                .contains("UNLINK")
                .doesNotContain("HGET', callback_data_key, 'markerKey");
    }

    @Test
    void orderScriptsIncrementVersionsAndOldCompletionCannotDeleteNewSnapshot()
            throws IOException {
        String markPaid = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/mark_paid.lua");
        String complete = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/order_persist_complete.lua");

        assertThat(markPaid)
                .contains("stateVersion")
                .contains("current_version + 1")
                .contains("order_id .. '#' .. next_version")
                .contains("LATE_TERMINAL")
                .doesNotContain("'closingDeadlineAt', ''");
        assertThat(complete)
                .contains("snapshot_version == token_version")
                .contains("status == 'PAID' or status == 'CANCELLED' or status == 'CLOSED'")
                .contains("UNLINK");
    }

    @Test
    void paymentRedisSourceDoesNotUseStreams() throws IOException {
        Path sourceRoot = PROJECT_ROOT.resolve(
                "ai-temperate-service/src/main/java/com/example/temperate/service/"
                        + "user/membership/payment");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            String source = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(MembershipPaymentRedisArchitectureTest::readUnchecked)
                    .reduce("", String::concat);
            assertThat(source)
                    .doesNotContain("opsForStream")
                    .doesNotContain("StreamOperations")
                    .doesNotContain("XREAD")
                    .doesNotContain("XADD");
        }
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read " + path, exception);
        }
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
