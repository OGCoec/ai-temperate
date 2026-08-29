package com.example.temperate.mapper.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderEntitlementResolution;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallback;
import com.example.temperate.model.user.membership.payment.MembershipPaymentRefundTerminalFact;
import com.example.temperate.model.user.membership.payment.MembershipPaymentCallbackWriteResult;
import com.example.temperate.model.auth.enums.MembershipTier;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 该集成测试是来验证会员支付批量 SQL 在真实 PostgreSQL 中保持状态版本单调，并区分回调新增、重复和错单。
 */
@Testcontainers(disabledWithoutDocker = true)
final class MembershipPaymentMapperIntegrationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("membership_payment_test")
            .withUsername("membership_payment_test")
            .withPassword("membership_payment_test_password");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void configure() throws Exception {
        applyMigrations();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @Test
    void orderAndCallbackRoundTripAllTenTimestampColumnsAtMicrosecondPrecision()
            throws SQLException {
        byte[] orderId = id((byte) 90);
        byte[] callbackId = id((byte) 91);
        OffsetDateTime micro = OffsetDateTime.parse("2026-08-23T18:22:00.123456Z");
        MembershipOrder candidate = order(
                orderId, 75L, "340e4a6f-28df-4d17-89a2-81f37f08c2d1");
        candidate.setStatus(MembershipOrderStatus.PAID);
        candidate.setProviderTradeNo("provider-microsecond");
        candidate.setPaymentStartedAt(micro);
        candidate.setExpiresAt(micro.plusMinutes(5));
        candidate.setClosingDeadlineAt(micro.plusMinutes(10));
        candidate.setPaidAt(micro.plusSeconds(1));
        candidate.setEntitlementResolution(MembershipOrderEntitlementResolution.APPLIED);
        candidate.setEntitlementResolvedAt(micro.plusSeconds(2));
        candidate.setCreatedAt(micro.minusSeconds(1));
        candidate.setUpdatedAt(micro.plusSeconds(2));

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper orderMapper = session.getMapper(MembershipOrderMapper.class);
            MembershipPaymentCallbackMapper callbackMapper =
                    session.getMapper(MembershipPaymentCallbackMapper.class);
            assertThat(orderMapper.insert(candidate)).isEqualTo(1);
            String callbackJson = callbacksJson("""
                    {"ordinal":1,"idHex":"%s","orderIdHex":"%s",
                     "providerTradeNo":"provider-microsecond","tradeStatus":"TRADE_SUCCESS",
                     "paidAmountYuan":20.00,"paidAt":"%s","receivedAt":"%s"}
                    """.formatted(
                    hex(callbackId), hex(orderId), micro.plusSeconds(1), micro.plusSeconds(3)));
            assertThat(callbackMapper.batchInsertOrResolve(callbackJson)).hasSize(1);
            assertThat(callbackMapper.batchResolve("""
                    [{"callbackIdHex":"%s","resolution":"APPLIED","resolvedAt":"%s"}]
                    """.formatted(hex(callbackId), micro.plusSeconds(4)))).isEqualTo(1);

            MembershipOrder persistedOrder = orderMapper.findById(orderId);
            MembershipPaymentCallback persistedCallback = callbackMapper
                    .findByIdsJsonForUpdate("[\"%s\"]".formatted(hex(callbackId)))
                    .getFirst();
            assertThat(List.of(
                    persistedOrder.getPaymentStartedAt(),
                    persistedOrder.getExpiresAt(),
                    persistedOrder.getClosingDeadlineAt(),
                    persistedOrder.getPaidAt(),
                    persistedOrder.getEntitlementResolvedAt(),
                    persistedOrder.getCreatedAt(),
                    persistedOrder.getUpdatedAt(),
                    persistedCallback.getPaidAt(),
                    persistedCallback.getReceivedAt(),
                    persistedCallback.getResolvedAt()))
                    .allSatisfy(value -> assertThat(value.getNano()).isEqualTo(123_456_000));
        }

        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                java.sql.ResultSet result = statement.executeQuery("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND ((table_name = 'membership_order'
                                AND column_name IN ('payment_started_at', 'expires_at',
                                    'closing_deadline_at', 'paid_at', 'entitlement_resolved_at',
                                    'created_at', 'updated_at'))
                            OR (table_name = 'membership_payment_callback'
                                AND column_name IN ('paid_at', 'received_at', 'resolved_at')))
                          AND datetime_precision = 6
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(10);
        }
    }

    @Test
    void batchAdvanceStateAcceptsMonotonicProgressAndFindsIdsInOneJsonBatch() {
        byte[] firstId = id((byte) 1);
        byte[] secondId = id((byte) 2);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper mapper = session.getMapper(MembershipOrderMapper.class);
            assertThat(mapper.insert(order(firstId, 17L, "550e8400-e29b-41d4-a716-446655440000")))
                    .isEqualTo(1);
            assertThat(mapper.insert(order(secondId, 18L, "4b6a6142-6b43-44d8-a53d-df2fe483b95e")))
                    .isEqualTo(1);

            String advance = """
                    [{"idHex":"%s","status":2,"providerTradeNo":"provider-1",\
                    "closingDeadlineAt":null,"paidAt":"2026-08-20T12:01:00Z",\
                    "stateVersion":2,"updatedAt":"2026-08-20T12:01:00Z"}]
                    """.formatted(hex(firstId));
            assertThat(mapper.batchAdvanceState(advance)).isEqualTo(1);

            String stale = """
                    [{"idHex":"%s","status":4,"providerTradeNo":null,\
                    "closingDeadlineAt":null,"paidAt":null,"stateVersion":1,\
                    "updatedAt":"2026-08-20T12:02:00Z"}]
                    """.formatted(hex(firstId));
            assertThat(mapper.batchAdvanceState(stale)).isZero();

            MembershipOrder persisted = mapper.findById(firstId);
            assertThat(persisted.getStatus()).isEqualTo(MembershipOrderStatus.PAID);
            assertThat(persisted.getStateVersion()).isEqualTo(2L);
            assertThat(mapper.findByIdsJson("[\"%s\",\"%s\"]"
                    .formatted(hex(firstId), hex(secondId)))).hasSize(2);
        }
    }

    @Test
    void latestPaidLookupUsesItsPartialIndexWithoutAnExtraSort() throws SQLException {
        long loginIdentityId = 900_001L;
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO userloginidentity (id)
                    VALUES (900001)
                    ON CONFLICT DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO membership_order (
                        id, login_identity_id, membership_tier, pay_amount_yuan, pay_type,
                        status, idempotency_key, provider_trade_no, payment_started_at,
                        expires_at, paid_at, entitlement_resolution, entitlement_resolved_at,
                        state_version, created_at, updated_at
                    )
                    SELECT
                        DECODE(LPAD(TO_HEX(1000000 + sequence_value), 32, '0'), 'hex'),
                        900001,
                        4,
                        20.00,
                        'alipay',
                        2,
                        MD5('latest-paid-idempotency-' || sequence_value)::UUID,
                        'latest-paid-provider-' || sequence_value,
                        TIMESTAMPTZ '2026-08-20 12:00:00+00',
                        TIMESTAMPTZ '2026-08-20 12:05:00+00',
                        TIMESTAMPTZ '2026-08-20 12:00:00+00'
                            + sequence_value * INTERVAL '1 millisecond',
                        'APPLIED',
                        TIMESTAMPTZ '2026-08-20 12:00:01+00'
                            + sequence_value * INTERVAL '1 millisecond',
                        2,
                        TIMESTAMPTZ '2026-08-20 11:00:00+00'
                            + sequence_value * INTERVAL '1 millisecond',
                        TIMESTAMPTZ '2026-08-20 12:00:01+00'
                            + sequence_value * INTERVAL '1 millisecond'
                    FROM GENERATE_SERIES(1, 10000) AS generated(sequence_value)
                    ON CONFLICT DO NOTHING
                    """);
            statement.execute("ANALYZE membership_order");
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrder latest = session.getMapper(MembershipOrderMapper.class)
                    .findLatestPaidOrder(loginIdentityId, MembershipTier.PLUS);

            assertThat(latest).isNotNull();
            assertThat(latest.getProviderTradeNo()).isEqualTo("latest-paid-provider-10000");
        }

        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                java.sql.ResultSet result = statement.executeQuery("""
                        EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
                        SELECT *
                        FROM membership_order
                        WHERE login_identity_id = 900001
                          AND membership_tier = 4
                          AND status = 2
                        ORDER BY paid_at DESC NULLS LAST, created_at DESC, id DESC
                        LIMIT 1
                        """)) {
            assertThat(result.next()).isTrue();
            String plan = result.getString(1);
            assertThat(plan)
                    .contains("idx_membership_order_latest_paid")
                    .doesNotContain("\"Node Type\": \"Sort\"");
        }
    }

    @Test
    void sameVersionRealtimeTerminalWinsOverConcurrentDatabasePaymentAttempt() {
        byte[] orderId = id((byte) 4);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper mapper = session.getMapper(MembershipOrderMapper.class);
            assertThat(mapper.insert(order(
                    orderId, 74L, "bc9a091f-7d42-468d-9c38-b928a6a7874e"))).isEqualTo(1);

            // Payment Attempt 与 Redis 取消分别从 v1 推进时会形成两个 v2；数据库刷盘必须让
            // 时间更晚且状态阶段更高的实时终态胜出，否则 Redis 清理后会把订单复活为 PENDING。
            OffsetDateTime attemptedAt = NOW.plusSeconds(3);
            assertThat(mapper.startPaymentAttemptIfAbsent(
                            orderId,
                            74L,
                            MembershipOrderStatus.PENDING_PAYMENT,
                            attemptedAt))
                    .isNotNull()
                    .extracting(MembershipOrder::getStateVersion)
                    .isEqualTo(2L);

            String cancelled = """
                    [{"idHex":"%s","status":3,"providerTradeNo":null,
                      "paymentStartedAt":null,"closingDeadlineAt":null,"paidAt":null,
                      "stateVersion":2,"updatedAt":"2026-08-20T12:00:04Z"}]
                    """.formatted(hex(orderId));
            assertThat(mapper.batchAdvanceState(cancelled)).isEqualTo(1);

            MembershipOrder persisted = mapper.findById(orderId);
            assertThat(persisted.getStatus()).isEqualTo(MembershipOrderStatus.CANCELLED);
            assertThat(persisted.getStateVersion()).isEqualTo(2L);
            assertThat(persisted.getPaymentStartedAt()).isEqualTo(attemptedAt);
            assertThat(persisted.getEntitlementResolution())
                    .isEqualTo(MembershipOrderEntitlementResolution.NOT_GRANTED);
            assertThat(persisted.getEntitlementResolvedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-08-20T12:00:04Z"));

            String attemptedReplay = """
                    [{"idHex":"%s","status":0,"providerTradeNo":null,
                      "paymentStartedAt":"2026-08-20T12:00:03Z",
                      "closingDeadlineAt":null,"paidAt":null,
                      "stateVersion":2,"updatedAt":"2026-08-20T12:00:05Z"}]
                    """.formatted(hex(orderId));
            assertThat(mapper.batchAdvanceState(attemptedReplay)).isZero();
            assertThat(mapper.findById(orderId).getStatus())
                    .isEqualTo(MembershipOrderStatus.CANCELLED);
        }
    }

    @Test
    void paymentAttemptStartsOnceBeforeExpiryAndRejectsExactExpiry() {
        byte[] startedId = id((byte) 5);
        byte[] expiredId = id((byte) 6);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper mapper = session.getMapper(MembershipOrderMapper.class);
            assertThat(mapper.insert(order(
                    startedId, 19L, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))).isEqualTo(1);
            assertThat(mapper.insert(order(
                    expiredId, 20L, "9f8c3f9e-c829-4a94-89f2-f38b93c7fbc1"))).isEqualTo(1);

            OffsetDateTime fourMinutesFiftyNine = NOW.plusMinutes(4).plusSeconds(59);
            MembershipOrder first = mapper.startPaymentAttemptIfAbsent(
                    startedId,
                    19L,
                    MembershipOrderStatus.PENDING_PAYMENT,
                    fourMinutesFiftyNine);
            MembershipOrder replay = mapper.startPaymentAttemptIfAbsent(
                    startedId,
                    19L,
                    MembershipOrderStatus.PENDING_PAYMENT,
                    fourMinutesFiftyNine.plusNanos(1));
            MembershipOrder exactExpiry = mapper.startPaymentAttemptIfAbsent(
                    expiredId,
                    20L,
                    MembershipOrderStatus.PENDING_PAYMENT,
                    NOW.plusMinutes(5));

            assertThat(first).isNotNull();
            assertThat(first.getPaymentStartedAt()).isEqualTo(fourMinutesFiftyNine);
            assertThat(first.getStateVersion()).isEqualTo(2L);
            assertThat(replay).isNull();
            assertThat(mapper.findById(startedId).getPaymentStartedAt())
                    .isEqualTo(fourMinutesFiftyNine);
            assertThat(exactExpiry).isNull();
        }
    }

    @Test
    void singleActiveIndexAllowsOneSameUserWinnerWithoutBlockingDifferentUsers()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch sameUserReady = new CountDownLatch(2);
            CountDownLatch sameUserStart = new CountDownLatch(1);
            Future<Integer> sameUserFirst = executor.submit(() -> concurrentInsert(
                    order(
                            id((byte) 61),
                            70L,
                            "c6c7e9a4-4692-480b-a3e7-a12cf0840021"),
                    sameUserReady,
                    sameUserStart));
            Future<Integer> sameUserSecond = executor.submit(() -> concurrentInsert(
                    order(
                            id((byte) 62),
                            70L,
                            "7d344798-185a-49fb-8d6e-dd6be810d356"),
                    sameUserReady,
                    sameUserStart));
            assertThat(sameUserReady.await(5, TimeUnit.SECONDS)).isTrue();
            sameUserStart.countDown();
            assertThat(sameUserFirst.get(10, TimeUnit.SECONDS)
                    + sameUserSecond.get(10, TimeUnit.SECONDS)).isEqualTo(1);

            CountDownLatch differentUsersReady = new CountDownLatch(2);
            CountDownLatch differentUsersStart = new CountDownLatch(1);
            Future<Integer> differentUserFirst = executor.submit(() -> concurrentInsert(
                    order(
                            id((byte) 63),
                            71L,
                            "b83e5bbd-a95a-4511-8410-a80c540263ba"),
                    differentUsersReady,
                    differentUsersStart));
            Future<Integer> differentUserSecond = executor.submit(() -> concurrentInsert(
                    order(
                            id((byte) 64),
                            72L,
                            "b5cc49af-d287-4217-aecb-7a359a3aaf23"),
                    differentUsersReady,
                    differentUsersStart));
            assertThat(differentUsersReady.await(5, TimeUnit.SECONDS)).isTrue();
            differentUsersStart.countDown();
            assertThat(differentUserFirst.get(10, TimeUnit.SECONDS)
                    + differentUserSecond.get(10, TimeUnit.SECONDS)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void callbackBatchResolvesBothUniqueKeysAndKeepsTheOriginalFacts() {
        byte[] firstOrderId = id((byte) 11);
        byte[] secondOrderId = id((byte) 12);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper orderMapper = session.getMapper(MembershipOrderMapper.class);
            MembershipPaymentCallbackMapper callbackMapper =
                    session.getMapper(MembershipPaymentCallbackMapper.class);
            assertThat(orderMapper.insert(order(
                    firstOrderId, 21L, "96b9fe15-45a2-4f48-a5d8-07d81f2bcd73"))).isEqualTo(1);
            assertThat(orderMapper.insert(order(
                    secondOrderId, 22L, "fa9de8da-4d8e-4a0a-b94d-cc9f82917af0"))).isEqualTo(1);

            String first = callbacksJson(callback(
                    1, id((byte) 21), firstOrderId, "provider-paid-1"));
            List<MembershipPaymentCallbackWriteResult> inserted =
                    callbackMapper.batchInsertOrResolve(first);
            assertThat(inserted)
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("INSERTED");
            assertThat(inserted.getFirst().getPersistedCallbackId())
                    .isEqualTo(id((byte) 21));

            String resolution = """
                    [{"callbackIdHex":"%s","resolution":"APPLIED",\
                    "resolvedAt":"2026-08-20T12:03:01Z"}]
                    """.formatted(hex(id((byte) 21)));
            assertThat(callbackMapper.batchResolve(resolution)).isEqualTo(1);
            assertThat(callbackMapper.batchResolve(resolution)).isEqualTo(1);

            assertThat(callbackMapper.batchInsertOrResolve(callbacksJson(
                    callback(1, id((byte) 21), firstOrderId, "provider-paid-1"))))
                    .allSatisfy(result -> assertThat(result.getSameCallback()).isTrue())
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("DUPLICATE");

            assertThat(callbackMapper.batchInsertOrResolve(callbacksJson(
                    callback(1, id((byte) 22), firstOrderId, "provider-paid-1"),
                    callback(2, id((byte) 23), firstOrderId, "provider-paid-2"),
                    callback(3, id((byte) 24), secondOrderId, "provider-paid-1"))))
                    .allSatisfy(result -> assertThat(result.getSameCallback()).isFalse())
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly(
                            "DUPLICATE",
                            "ORDER_DUPLICATE",
                            "PROVIDER_TRADE_REUSED");

            assertThat(callbackMapper.batchInsertOrResolve(callbacksJson(
                    callback(1, id((byte) 25), secondOrderId, "provider-paid-2"))))
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("INSERTED");
            assertThat(callbackMapper.batchInsertOrResolve(callbacksJson(
                    callback(1, id((byte) 26), firstOrderId, "provider-paid-2"))))
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("IDENTITY_CONFLICT");
            assertThat(callbackMapper.batchInsertOrResolve(first).getFirst().getResolution())
                    .isEqualTo("APPLIED");
        }
    }

    @Test
    void callbackBatchPreservesSameStatementIdentityConflictSemantics()
            throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO userloginidentity (id)
                    SELECT generate_series(1100, 1103)
                    ON CONFLICT DO NOTHING
                    """);
        }
        byte[] firstOrderId = id((byte) 101);
        byte[] secondOrderId = id((byte) 102);
        byte[] thirdOrderId = id((byte) 103);
        byte[] fourthOrderId = id((byte) 104);
        byte[] firstCallbackId = id((byte) 105);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper orderMapper = session.getMapper(MembershipOrderMapper.class);
            MembershipPaymentCallbackMapper callbackMapper =
                    session.getMapper(MembershipPaymentCallbackMapper.class);
            assertThat(orderMapper.insert(order(
                    firstOrderId, 1100L, "3ba96fc7-5d8e-44ba-9bc7-b72992fec1d2"))).isEqualTo(1);
            assertThat(orderMapper.insert(order(
                    secondOrderId, 1101L, "5db623ba-d89d-4f62-b5ed-9a51c532397f"))).isEqualTo(1);
            assertThat(orderMapper.insert(order(
                    thirdOrderId, 1102L, "c4779ed6-fd69-43bb-99af-93d676e45095"))).isEqualTo(1);
            assertThat(orderMapper.insert(order(
                    fourthOrderId, 1103L, "39762b4e-f576-427b-8a1c-6a4c2234a76d"))).isEqualTo(1);

            // DML CTE 只返回一条物理新事实，但同一 callbackId 的重复输入继续共享原有 INSERTED 语义。
            assertThat(callbackMapper.batchInsertOrResolve(callbacksJson(
                    callback(1, firstCallbackId, firstOrderId, "provider-same-id"),
                    callback(2, firstCallbackId, firstOrderId, "provider-same-id"))))
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("INSERTED", "INSERTED");

            assertThat(callbackMapper.batchInsertOrResolve(callbacksJson(
                    callback(1, id((byte) 106), secondOrderId, "provider-same-order-1"),
                    callback(2, id((byte) 107), secondOrderId, "provider-same-order-2"))))
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("INSERTED", "ORDER_DUPLICATE");

            assertThat(callbackMapper.batchInsertOrResolve(callbacksJson(
                    callback(1, id((byte) 108), thirdOrderId, "provider-reused-in-batch"),
                    callback(2, id((byte) 109), fourthOrderId, "provider-reused-in-batch"))))
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("INSERTED", "PROVIDER_TRADE_REUSED");

            assertThat(callbackMapper.batchInsertOrResolve(callbacksJson(
                    callback(1, firstCallbackId, fourthOrderId, "provider-callback-id-reused"))))
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("CALLBACK_ID_REUSED");
        }
    }

    @Test
    void refundTerminalFactsReturnOnlyDatabaseAuthorityNeededForMissingSnapshotRecovery() {
        byte[] orderId = id((byte) 27);
        byte[] callbackId = id((byte) 28);
        String providerTradeNo = "provider-refund-terminal-fact";
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper orderMapper = session.getMapper(MembershipOrderMapper.class);
            MembershipPaymentCallbackMapper callbackMapper =
                    session.getMapper(MembershipPaymentCallbackMapper.class);
            assertThat(orderMapper.insert(order(
                    orderId, 76L, "406cc424-c3d5-446f-bcb7-f23830628ef5"))).isEqualTo(1);
            assertThat(callbackMapper.batchInsertOrResolve(callbacksJson(
                    callback(1, callbackId, orderId, providerTradeNo))))
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("INSERTED");
            assertThat(callbackMapper.batchResolve("""
                    [{"callbackIdHex":"%s","resolution":"REFUND_REQUIRED",
                      "resolvedAt":"2026-08-20T12:09:00Z"}]
                    """.formatted(hex(callbackId)))).isEqualTo(1);
            assertThat(orderMapper.batchAdvanceState("""
                    [{"idHex":"%s","status":4,"providerTradeNo":null,
                      "closingDeadlineAt":null,"paidAt":null,"stateVersion":2,
                      "updatedAt":"2026-08-20T12:09:00Z"}]
                    """.formatted(hex(orderId)))).isEqualTo(1);
            assertThat(orderMapper.batchResolveEntitlements("""
                    [{"orderIdHex":"%s","resolution":"REFUND_REQUIRED",
                      "providerTradeNo":"%s","resolvedAt":"2026-08-20T12:09:00Z"}]
                    """.formatted(hex(orderId), providerTradeNo))).isEqualTo(1);

            assertThat(callbackMapper.findRefundTerminalFactsByIdsJson(
                    "[\"%s\"]".formatted(hex(callbackId))))
                    .singleElement()
                    .satisfies(fact -> {
                        assertThat(fact.getCallbackId()).isEqualTo(callbackId);
                        assertThat(fact.getOrderId()).isEqualTo(orderId);
                        assertThat(fact.getProviderTradeNo()).isEqualTo(providerTradeNo);
                        assertThat(fact.getCallbackResolution()).isEqualTo("REFUND_REQUIRED");
                        assertThat(fact.getOrderStatus()).isEqualTo(MembershipOrderStatus.CLOSED);
                        assertThat(fact.getOrderEntitlementResolution())
                                .isEqualTo(MembershipOrderEntitlementResolution.REFUND_REQUIRED);
                        assertThat(fact.getOrderProviderTradeNo()).isNull();
                    });
        }
    }

    @Test
    void callbackAndPaidStateBatchesPersistMultipleOrdersTogether() {
        byte[] firstOrderId = id((byte) 31);
        byte[] secondOrderId = id((byte) 32);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper orderMapper = session.getMapper(MembershipOrderMapper.class);
            MembershipPaymentCallbackMapper callbackMapper =
                    session.getMapper(MembershipPaymentCallbackMapper.class);
            assertThat(orderMapper.insert(order(
                    firstOrderId, 23L, "671d861b-1370-41cc-a35d-685960b0ada1"))).isEqualTo(1);
            assertThat(orderMapper.insert(order(
                    secondOrderId, 24L, "7cc48645-7ad8-4242-8e2a-b06f44c8e3c7"))).isEqualTo(1);

            String callbackBatch = callbacksJson(
                    callback(1, id((byte) 41), firstOrderId, "provider-batch-1"),
                    callback(2, id((byte) 42), secondOrderId, "provider-batch-2"));
            assertThat(callbackMapper.batchInsertOrResolve(callbackBatch))
                    .extracting(MembershipPaymentCallbackWriteResult::getOutcome)
                    .containsExactly("INSERTED", "INSERTED");

            String paidBatch = """
                    [
                      {"idHex":"%s","status":2,"providerTradeNo":"provider-batch-1",\
                       "closingDeadlineAt":null,"paidAt":"2026-08-20T12:04:00Z",\
                       "stateVersion":2,"updatedAt":"2026-08-20T12:04:00Z"},
                      {"idHex":"%s","status":2,"providerTradeNo":"provider-batch-2",\
                       "closingDeadlineAt":null,"paidAt":"2026-08-20T12:04:00Z",\
                       "stateVersion":2,"updatedAt":"2026-08-20T12:04:00Z"}
                    ]
                    """.formatted(hex(firstOrderId), hex(secondOrderId));
            assertThat(orderMapper.batchAdvanceState(paidBatch)).isEqualTo(2);
            assertThat(orderMapper.findByIdsJson("[\"%s\",\"%s\"]"
                            .formatted(hex(firstOrderId), hex(secondOrderId))))
                    .extracting(MembershipOrder::getStatus)
                    .containsExactly(
                            MembershipOrderStatus.PAID,
                            MembershipOrderStatus.PAID);
        }
    }

    @Test
    void callbackBatchKeepsOneHundredResultsOrderedForInsertAndDuplicateReplay()
            throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO userloginidentity (id)
                    SELECT generate_series(1000, 1099)
                    ON CONFLICT DO NOTHING
                    """);
        }
        List<String> callbacks = new ArrayList<>(100);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper orderMapper = session.getMapper(MembershipOrderMapper.class);
            MembershipPaymentCallbackMapper callbackMapper =
                    session.getMapper(MembershipPaymentCallbackMapper.class);
            for (int index = 0; index < 100; index++) {
                byte[] orderId = numericId(10_000L + index);
                byte[] callbackId = numericId(20_000L + index);
                assertThat(orderMapper.insert(order(
                        orderId,
                        1000L + index,
                        UUID.nameUUIDFromBytes(("callback-batch-order-" + index)
                                .getBytes(StandardCharsets.UTF_8)).toString()))).isEqualTo(1);
                callbacks.add(callback(
                        index + 1,
                        callbackId,
                        orderId,
                        "provider-callback-batch-" + index));
            }
            String callbackBatch = callbacksJson(callbacks.toArray(String[]::new));

            List<MembershipPaymentCallbackWriteResult> inserted =
                    callbackMapper.batchInsertOrResolve(callbackBatch);
            List<MembershipPaymentCallbackWriteResult> duplicate =
                    callbackMapper.batchInsertOrResolve(callbackBatch);

            assertThat(inserted).hasSize(100);
            assertThat(duplicate).hasSize(100);
            for (int index = 0; index < 100; index++) {
                assertThat(inserted.get(index).getOrdinal()).isEqualTo(index + 1);
                assertThat(inserted.get(index).getOutcome()).isEqualTo("INSERTED");
                assertThat(duplicate.get(index).getOrdinal()).isEqualTo(index + 1);
                assertThat(duplicate.get(index).getOutcome())
                        .as("duplicate outcome at index %s", index)
                        .isEqualTo("DUPLICATE");
            }
        }
    }

    @Test
    void singleActiveIndexBlocksUntilTerminalOrPaidEntitlementIsResolved() {
        byte[] firstId = id((byte) 51);
        byte[] secondId = id((byte) 52);
        byte[] thirdId = id((byte) 53);
        byte[] closedId = id((byte) 54);
        byte[] afterClosedId = id((byte) 55);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper mapper = session.getMapper(MembershipOrderMapper.class);
            assertThat(mapper.insert(order(
                    firstId, 25L, "74f1f0a0-24df-4c52-8d95-87eb2fb41211"))).isEqualTo(1);
            assertThat(mapper.insert(order(
                    secondId, 25L, "8beaf67c-159f-4316-b70f-439147e3d2af"))).isZero();

            assertThat(mapper.batchAdvanceState("""
                    [{"idHex":"%s","status":1,"providerTradeNo":null,
                      "closingDeadlineAt":"2026-08-20T12:10:00Z","paidAt":null,
                      "stateVersion":2,
                      "updatedAt":"2026-08-20T12:05:00Z"}]
                    """.formatted(hex(firstId)))).isEqualTo(1);
            assertThat(mapper.insert(order(
                    secondId, 25L, "8beaf67c-159f-4316-b70f-439147e3d2af"))).isZero();

            assertThat(mapper.batchAdvanceState("""
                    [{"idHex":"%s","status":3,"providerTradeNo":null,
                      "closingDeadlineAt":null,"paidAt":null,"stateVersion":3,
                      "updatedAt":"2026-08-20T12:05:01Z"}]
                    """.formatted(hex(firstId)))).isEqualTo(1);
            assertThat(mapper.insert(order(
                    secondId, 25L, "8beaf67c-159f-4316-b70f-439147e3d2af"))).isEqualTo(1);

            assertThat(mapper.batchAdvanceState("""
                    [{"idHex":"%s","status":2,"providerTradeNo":"provider-active-2",
                      "closingDeadlineAt":null,"paidAt":"2026-08-20T12:06:00Z",
                      "stateVersion":2,"updatedAt":"2026-08-20T12:06:00Z"}]
                    """.formatted(hex(secondId)))).isEqualTo(1);
            assertThat(mapper.insert(order(
                    thirdId, 25L, "247ba0ea-1e85-4fef-970f-e24208003d78"))).isZero();

            assertThat(mapper.batchResolveEntitlements("""
                    [{"orderIdHex":"%s","resolution":"APPLIED",
                      "providerTradeNo":"provider-active-2",
                      "resolvedAt":"2026-08-20T12:06:01Z"}]
                    """.formatted(hex(secondId)))).isEqualTo(1);
            assertThat(mapper.insert(order(
                    thirdId, 25L, "247ba0ea-1e85-4fef-970f-e24208003d78"))).isEqualTo(1);

            assertThat(mapper.insert(order(
                    closedId, 73L, "ad0db724-003d-4f63-8964-0a65afea458e"))).isEqualTo(1);
            assertThat(mapper.batchAdvanceState("""
                    [{"idHex":"%s","status":4,"providerTradeNo":"provider-not-granted",
                      "closingDeadlineAt":null,"paidAt":null,"stateVersion":2,
                      "updatedAt":"2026-08-20T12:07:00Z"}]
                    """.formatted(hex(closedId)))).isEqualTo(1);
            MembershipOrder closed = mapper.findById(closedId);
            assertThat(closed.getProviderTradeNo()).isNull();
            assertThat(closed.getEntitlementResolution())
                    .isEqualTo(MembershipOrderEntitlementResolution.NOT_GRANTED);
            assertThat(closed.getEntitlementResolvedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-08-20T12:07:00Z"));
            assertThat(mapper.insert(order(
                    afterClosedId, 73L, "f81f9496-c4f1-470d-8222-03fbd3717986"))).isEqualTo(1);
        }
    }

    @Test
    void terminalNotGrantedCanOnlyAdvanceToRefundRequired() {
        byte[] refundId = id((byte) 56);
        byte[] appliedId = id((byte) 57);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper mapper = session.getMapper(MembershipOrderMapper.class);
            assertThat(mapper.insert(order(
                    refundId, 70L, "bb40faaf-73d1-48d4-a8a6-c50886794be7"))).isEqualTo(1);
            assertThat(mapper.insert(order(
                    appliedId, 71L, "6dfbb942-1044-4cd5-aa9d-e5fa572087cf"))).isEqualTo(1);

            assertThat(mapper.batchAdvanceState("""
                    [
                      {"idHex":"%s","status":4,"providerTradeNo":null,
                       "closingDeadlineAt":null,"paidAt":null,"stateVersion":2,
                       "updatedAt":"2026-08-20T12:08:00Z"},
                      {"idHex":"%s","status":3,"providerTradeNo":null,
                       "closingDeadlineAt":null,"paidAt":null,"stateVersion":2,
                       "updatedAt":"2026-08-20T12:08:01Z"}
                    ]
                    """.formatted(hex(refundId), hex(appliedId)))).isEqualTo(2);

            assertThat(mapper.batchResolveEntitlements("""
                    [{"orderIdHex":"%s","resolution":"REFUND_REQUIRED",
                      "providerTradeNo":"provider-refund-terminal",
                      "resolvedAt":"2026-08-20T12:09:00Z"}]
                    """.formatted(hex(refundId)))).isEqualTo(1);
            MembershipOrder refunded = mapper.findById(refundId);
            assertThat(refunded.getProviderTradeNo()).isNull();
            assertThat(refunded.getEntitlementResolution())
                    .isEqualTo(MembershipOrderEntitlementResolution.REFUND_REQUIRED);
            assertThat(refunded.getEntitlementResolvedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-08-20T12:09:00Z"));
            assertThat(refunded.getUpdatedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-08-20T12:09:00Z"));
            assertThat(mapper.batchResolveEntitlements("""
                    [{"orderIdHex":"%s","resolution":"REFUND_REQUIRED",
                      "providerTradeNo":"provider-refund-terminal",
                      "resolvedAt":"2026-08-20T12:10:00Z"}]
                    """.formatted(hex(refundId)))).isEqualTo(1);
            assertThat(mapper.findById(refundId).getUpdatedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-08-20T12:09:00Z"));
            assertThat(mapper.batchResolveEntitlements("""
                    [{"orderIdHex":"%s","resolution":"REFUND_REQUIRED",
                      "providerTradeNo":"provider-conflicting-replay",
                      "resolvedAt":"2026-08-20T12:09:00Z"}]
                    """.formatted(hex(refundId)))).isEqualTo(1);
            assertThat(mapper.findById(refundId).getProviderTradeNo())
                    .isNull();

            assertThat(mapper.batchResolveEntitlements("""
                    [{"orderIdHex":"%s","resolution":"APPLIED",
                      "resolvedAt":"2026-08-20T12:09:01Z"}]
                    """.formatted(hex(appliedId)))).isZero();
            assertThat(mapper.findById(appliedId).getEntitlementResolution())
                    .isEqualTo(MembershipOrderEntitlementResolution.NOT_GRANTED);
        }
    }

    @Test
    void firstRefundRequiredResolutionUpdatesOrderTimestampExactlyOnce() {
        byte[] orderId = id((byte) 58);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper mapper = session.getMapper(MembershipOrderMapper.class);
            assertThat(mapper.insert(order(
                    orderId, 76L, "1e6c87cb-8706-4f35-8ef1-bc23991763ca"))).isEqualTo(1);

            assertThat(mapper.batchResolveEntitlements("""
                    [{"orderIdHex":"%s","resolution":"REFUND_REQUIRED",
                      "providerTradeNo":"provider-refund-first",
                      "resolvedAt":"2026-08-20T12:11:00Z"}]
                    """.formatted(hex(orderId)))).isEqualTo(1);
            MembershipOrder resolved = mapper.findById(orderId);
            assertThat(resolved.getProviderTradeNo()).isNull();
            assertThat(resolved.getEntitlementResolution())
                    .isEqualTo(MembershipOrderEntitlementResolution.REFUND_REQUIRED);
            assertThat(resolved.getEntitlementResolvedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-08-20T12:11:00Z"));
            assertThat(resolved.getUpdatedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-08-20T12:11:00Z"));

            assertThat(mapper.batchResolveEntitlements("""
                    [{"orderIdHex":"%s","resolution":"REFUND_REQUIRED",
                      "providerTradeNo":"provider-refund-first",
                      "resolvedAt":"2026-08-20T12:12:00Z"}]
                    """.formatted(hex(orderId)))).isEqualTo(1);
            assertThat(mapper.findById(orderId).getUpdatedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-08-20T12:11:00Z"));
        }
    }

    private static MembershipOrder order(byte[] id, long userId, String idempotencyKey) {
        MembershipOrder order = new MembershipOrder();
        order.setId(id);
        order.setLoginIdentityId(userId);
        order.setMembershipTier(MembershipTier.PLUS);
        order.setPayAmountYuan(new BigDecimal("20.00"));
        order.setPayType("alipay");
        order.setStatus(MembershipOrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(UUID.fromString(idempotencyKey));
        order.setExpiresAt(NOW.plusMinutes(5));
        order.setStateVersion(1L);
        order.setCreatedAt(NOW);
        order.setUpdatedAt(NOW);
        return order;
    }

    private static String callback(
            int ordinal,
            byte[] callbackId,
            byte[] orderId,
            String providerTradeNo) {
        return """
                {"ordinal":%d,"idHex":"%s","orderIdHex":"%s",\
                "providerTradeNo":"%s","tradeStatus":"TRADE_SUCCESS",\
                "paidAmountYuan":20.00,"paidAt":"2026-08-20T12:02:59Z",\
                "receivedAt":"2026-08-20T12:03:00Z"}
                """.formatted(ordinal, hex(callbackId), hex(orderId), providerTradeNo);
    }

    private static String callbacksJson(String... callbacks) {
        return "[" + String.join(",", callbacks) + "]";
    }

    private static byte[] id(byte value) {
        byte[] id = new byte[16];
        Arrays.fill(id, value);
        return id;
    }

    private static byte[] numericId(long value) {
        return java.nio.ByteBuffer.allocate(16)
                .putLong(0x6d656d6265727368L)
                .putLong(value)
                .array();
    }

    /** 每个任务使用独立事务，让 PostgreSQL 部分唯一索引直接裁决跨连接并发写入。 */
    private static int concurrentInsert(
            MembershipOrder candidate,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent membership order start timed out.");
            }
            int inserted = session.getMapper(MembershipOrderMapper.class).insert(candidate);
            session.commit();
            return inserted;
        }
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static void applyMigrations() throws IOException, SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE userloginidentity (
                        id BIGINT PRIMARY KEY
                    )
                    """);
            statement.execute("""
                    INSERT INTO userloginidentity (id)
                    VALUES
                        (17), (18), (19), (20),
                        (21), (22), (23), (24), (25),
                        (70), (71), (72), (73), (74), (75), (76)
                    """);
            statement.execute(read("sql/018_create_membership_order.sql"));
            statement.execute(read("sql/019_create_membership_payment_callback.sql"));
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory() throws IOException {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                POSTGRES.getDriverClassName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Environment environment = new Environment(
                "membership-payment-test",
                new JdbcTransactionFactory(),
                dataSource);
        Configuration configuration = new Configuration(environment);
        addMapper(configuration, "mapper/user/membership/payment/MembershipOrderMapper.xml");
        addMapper(
                configuration,
                "mapper/user/membership/payment/MembershipPaymentCallbackMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void addMapper(Configuration configuration, String resource)
            throws IOException {
        try (InputStream inputStream = MembershipPaymentMapperIntegrationTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(inputStream, "Missing mapper XML: " + resource);
            new XMLMapperBuilder(
                    inputStream,
                    configuration,
                    resource,
                    configuration.getSqlFragments())
                    .parse();
        }
    }

    private static Connection openConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("sql"))
                    && Files.isDirectory(current.resolve("ai-temperate-mapper"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
