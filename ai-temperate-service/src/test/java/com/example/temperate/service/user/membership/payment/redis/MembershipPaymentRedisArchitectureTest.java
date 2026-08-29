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
                .contains("'MGET', idempotency_key, order_idempotency_key, provider_trade_idempotency_key")
                .doesNotContain("redis.call('GET', order_idempotency_key)")
                .doesNotContain("redis.call('GET', provider_trade_idempotency_key)")
                .doesNotContain("redis.call('SET', marker_key, existing");
        assertThat(claim)
                .contains("ZPOPMIN")
                .contains("ZADD");
        assertThat(recover)
                .contains("ZRANGEBYSCORE")
                .contains("LIMIT");
        assertThat(complete)
                .contains("ZSCORE")
                .contains("expected_score")
                .contains("redis.call('GET', marker_key) == callback_id")
                .contains("redis.call('HGET', provider_result_key, 'callbackId') == callback_id")
                .contains("provider_result_action == 'REMOVE'")
                .contains("provider_result_action == 'RESET_UNPAID'")
                .contains("UNLINK")
                .doesNotContain("remove_provider_result")
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
                .doesNotContain("redis.call('EXISTS', snapshot_key)")
                .contains("current_version + 1")
                .contains("order_id .. '#' .. next_version")
                .contains("LATE_TERMINAL")
                .doesNotContain("'closingDeadlineAt', ''");
        assertThat(complete)
                .contains("tonumber(snapshot[1]) == token_versions[index]")
                .contains("snapshot[2] == 'PAID'")
                .contains("UNLINK");
    }

    @Test
    void orderTransitionsSeparateMicrosecondFactsFromMillisecondSchedulingScores()
            throws IOException {
        String store = read("ai-temperate-service/src/main/java/com/example/temperate/service/"
                + "user/membership/payment/store/impl/RedisMembershipOrderSnapshotStore.java");
        String markPaid = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/mark_paid.lua");
        String cancel = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/cancel_order.lua");
        String startClosing = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/start_closing.lua");
        String finalizeClosing = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/finalize_closing.lua");

        assertThat(store)
                .contains("epochMicros(command.paidAt(), \"paidAt\")")
                .contains("epochMicros(command.changedAt(), \"changedAt\")")
                .contains("epochMillis(command.changedAt(), \"changedAt\")");
        assertThat(markPaid)
                .contains("local paid_at_micros")
                .contains("local changed_at_micros")
                .contains("local dirty_score_millis")
                .contains("'paidAt', paid_at_micros")
                .contains("'updatedAt', changed_at_micros")
                .contains("'ZADD', dirty_key, dirty_score_millis");
        assertThat(cancel)
                .contains("local changed_at_micros")
                .contains("local dirty_score_millis")
                .contains("redis.call('HMGET', snapshot_key, 'status', 'stateVersion')")
                .doesNotContain("redis.call('EXISTS', snapshot_key)")
                .contains("'updatedAt', changed_at_micros")
                .contains("'ZADD', dirty_key, dirty_score_millis");
        assertThat(startClosing)
                .contains("local closing_deadline_at_micros")
                .contains("local changed_at_micros")
                .contains("local dirty_score_millis")
                .contains("redis.call('HMGET', snapshot_key, 'status', 'stateVersion')")
                .doesNotContain("redis.call('EXISTS', snapshot_key)")
                .contains("'closingDeadlineAt', closing_deadline_at_micros")
                .contains("'updatedAt', changed_at_micros")
                .contains("'ZADD', dirty_key, dirty_score_millis");
        assertThat(finalizeClosing)
                .contains("local changed_at_micros")
                .contains("local dirty_score_millis")
                .contains("local snapshot = redis.call('HMGET', snapshot_key")
                .doesNotContain("redis.call('EXISTS', snapshot_key)")
                .contains("deadline > tonumber(changed_at_micros)")
                .contains("'updatedAt', changed_at_micros")
                .contains("'ZADD', dirty_key, dirty_score_millis");
    }

    @Test
    void httpOrderWritesUseAtomicPutAndGetWithoutImmediateRedisReread()
            throws IOException {
        String orderService = read("ai-temperate-service/src/main/java/com/example/temperate/"
                + "service/user/membership/payment/order/impl/MembershipOrderServiceImpl.java");
        String attemptService = read("ai-temperate-service/src/main/java/com/example/temperate/"
                + "service/user/membership/payment/order/impl/"
                + "MembershipPaymentAttemptServiceImpl.java");
        String lookupService = read("ai-temperate-service/src/main/java/com/example/temperate/"
                + "service/user/membership/payment/order/impl/"
                + "MembershipPaymentOrderLookupServiceImpl.java");
        String paymentPatch = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/patch_payment_attempt.lua");
        String putAndGet = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/put_and_get_order_snapshot.lua");
        String codec = read("ai-temperate-service/src/main/java/com/example/temperate/"
                + "service/user/membership/payment/store/impl/"
                + "MembershipPaymentRedisCodec.java");

        assertThat(orderService)
                .contains("snapshotWriteCoordinator.putAndGet(databaseSnapshot)")
                .doesNotContain("snapshotStore.put(databaseSnapshot)");
        assertThat(attemptService)
                .contains("snapshotWriteCoordinator.patchPaymentAttempt(databaseSnapshot)")
                .contains("snapshotStore.findRealtimeGuard")
                .doesNotContain("snapshotStore.put(databaseSnapshot)");
        assertThat(lookupService)
                .contains("snapshotWriteCoordinator.putAndGet(databaseSnapshot)")
                .doesNotContain("snapshotStore.put(databaseSnapshot)");
        assertThat(paymentPatch)
                .contains("local guard = redis.call('HMGET', snapshot_key")
                .contains("'loginIdentityId', 'status', 'paymentStartedAt', 'stateVersion'")
                .contains("return {'APPLIED'}")
                .contains("return {'UNCHANGED'}")
                .doesNotContain("local values = redis.call('HMGET', snapshot_key, unpack(order_fields))");
        assertThat(putAndGet)
                .contains("return {outcome}")
                .contains("local current_snapshot = redis.call('HMGET', snapshot_key, unpack(order_fields))");
        assertThat(codec)
                .contains("MembershipOrderSnapshot submittedSnapshot")
                .contains("return new MembershipOrderSnapshotWriteResult(outcome, submittedSnapshot)");
    }

    @Test
    void batchWritesUseBoundedPipelinesAndSingleEntityScripts()
            throws IOException {
        String orderStore = read("ai-temperate-service/src/main/java/com/example/temperate/"
                + "service/user/membership/payment/store/impl/"
                + "RedisMembershipOrderSnapshotStore.java");
        String callbackQueue = read("ai-temperate-service/src/main/java/com/example/temperate/"
                + "service/user/membership/payment/store/impl/RedisPaymentCallbackQueue.java");
        String orderPersistenceQueue = read("ai-temperate-service/src/main/java/com/example/temperate/"
                + "service/user/membership/payment/store/impl/RedisOrderPersistenceQueue.java");
        String unappliedStore = read("ai-temperate-service/src/main/java/com/example/temperate/"
                + "service/user/membership/payment/store/impl/"
                + "RedisMembershipPaymentUnappliedCallbackStore.java");
        String putOrder = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/put_order_snapshot.lua");
        String putProvider = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/put_provider_result.lua");
        String enqueue = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/enqueue_callback.lua");
        String complete = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/callback_complete.lua");
        String finalizeRefund = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/finalize_refund_required.lua");
        String releaseRejected = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/release_rejected_callback.lua");
        String releaseMissingRefund = read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/release_missing_refund_required.lua");

        assertThat(orderStore)
                .contains("GENERAL_PIPELINE_BATCH_SIZE = 192")
                .contains("executeMixedWritePipeline")
                .contains("executePipelined")
                .doesNotContain("put_order_snapshots.lua")
                .doesNotContain("mark_paid_batch.lua");
        assertThat(callbackQueue)
                .contains("PIPELINE_BATCH_SIZE = 50")
                .contains("executePipelined");
        assertThat(orderPersistenceQueue)
                .contains("MAXIMUM_BATCH = 100")
                .doesNotContain("MAXIMUM_BATCH = 500");
        assertThat(unappliedStore)
                .contains("MAXIMUM_BATCH = 500")
                .contains("PIPELINE_BATCH_SIZE = 50")
                .contains("Collection<MembershipPaymentRefundRequiredFinalizationCommand>")
                .contains("Collection<MembershipPaymentRejectedCallbackReleaseCommand>")
                .contains("executePipelined");
        assertThat(putOrder)
                .contains("redis.call('HSET', snapshot_key, unpack(fields))")
                .doesNotContain("redis.call('HSET', snapshot_key, ARGV[argument_index]");
        assertThat(putProvider)
                .contains("redis.call('HSET', provider_key, unpack(fields))")
                .doesNotContain("redis.call('HSET', provider_key, ARGV[argument_index]");
        assertThat(enqueue)
                .contains("redis.call('HSET', callback_data_key, unpack(callback_fields))")
                .contains("redis.call('HSET', provider_result_key, unpack(provider_fields))");
        assertThat(complete)
                .doesNotContain("for index = 1, count do")
                .doesNotContain("local count = tonumber(ARGV[1])");
        assertSingleOrderScriptWithoutCollectionLoop(finalizeRefund);
        assertSingleOrderScriptWithoutCollectionLoop(releaseRejected);
        assertSingleOrderScriptWithoutCollectionLoop(releaseMissingRefund);
        assertThat(releaseMissingRefund)
                .contains("CLAIM_MISMATCH")
                .contains("CALLBACK_CONFLICT")
                .doesNotContain("ZREM")
                .doesNotContain("ZADD");
    }

    @Test
    void zsetBatchScriptsDoNotCallPerMemberScoreOrMutationCommands()
            throws IOException {
        for (String name : java.util.List.of(
                "callback_claim.lua",
                "callback_recover.lua",
                "callback_requeue.lua",
                "order_persist_claim.lua",
                "order_persist_recover.lua",
                "order_persist_requeue.lua")) {
            String script = read("ai-temperate-service/src/main/resources/"
                    + "lua/membership-payment/" + name);
            assertThat(script)
                    .doesNotContain("redis.call('ZSCORE'")
                    .doesNotContain("redis.call('ZREM', processing_key,")
                    .doesNotContain("redis.call('ZADD', ready_key, ready_at,")
                    .doesNotContain("redis.call('ZADD', dirty_key, ready_at,");
        }
        assertThat(read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/callback_claim.lua"))
                .contains("ZPOPMIN");
        assertThat(read("ai-temperate-service/src/main/resources/"
                + "lua/membership-payment/order_persist_claim.lua"))
                .contains("ZPOPMIN");
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

    private static void assertSingleOrderScriptWithoutCollectionLoop(String script) {
        assertThat(script)
                .doesNotContain("for ")
                .doesNotContain("while ")
                .doesNotContain("pairs(")
                .doesNotContain("ipairs(")
                .doesNotContain("local count =")
                .doesNotContain("ARGV[argument_index]");
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
