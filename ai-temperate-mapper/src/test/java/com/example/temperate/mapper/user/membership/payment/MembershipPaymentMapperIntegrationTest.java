package com.example.temperate.mapper.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
    void batchAdvanceStateOnlyAcceptsHigherVersionAndFindsIdsInOneJsonBatch() {
        byte[] firstId = id((byte) 1);
        byte[] secondId = id((byte) 2);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper mapper = session.getMapper(MembershipOrderMapper.class);
            assertThat(mapper.insert(order(firstId, 17L, "550e8400-e29b-41d4-a716-446655440000")))
                    .isEqualTo(1);
            assertThat(mapper.insert(order(secondId, 17L, "4b6a6142-6b43-44d8-a53d-df2fe483b95e")))
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
    void paymentAttemptStartsOnceBeforeExpiryAndRejectsExactExpiry() {
        byte[] startedId = id((byte) 5);
        byte[] expiredId = id((byte) 6);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper mapper = session.getMapper(MembershipOrderMapper.class);
            assertThat(mapper.insert(order(
                    startedId, 17L, "f47ac10b-58cc-4372-a567-0e02b2c3d479"))).isEqualTo(1);
            assertThat(mapper.insert(order(
                    expiredId, 17L, "9f8c3f9e-c829-4a94-89f2-f38b93c7fbc1"))).isEqualTo(1);

            OffsetDateTime fourMinutesFiftyNine = NOW.plusMinutes(4).plusSeconds(59);
            MembershipOrder first = mapper.startPaymentAttemptIfAbsent(
                    startedId,
                    17L,
                    MembershipOrderStatus.PENDING_PAYMENT,
                    fourMinutesFiftyNine);
            MembershipOrder replay = mapper.startPaymentAttemptIfAbsent(
                    startedId,
                    17L,
                    MembershipOrderStatus.PENDING_PAYMENT,
                    fourMinutesFiftyNine.plusNanos(1));
            MembershipOrder exactExpiry = mapper.startPaymentAttemptIfAbsent(
                    expiredId,
                    17L,
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
    void callbackBatchResolvesBothUniqueKeysAndKeepsTheOriginalFacts() {
        byte[] firstOrderId = id((byte) 11);
        byte[] secondOrderId = id((byte) 12);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper orderMapper = session.getMapper(MembershipOrderMapper.class);
            MembershipPaymentCallbackMapper callbackMapper =
                    session.getMapper(MembershipPaymentCallbackMapper.class);
            assertThat(orderMapper.insert(order(
                    firstOrderId, 23L, "96b9fe15-45a2-4f48-a5d8-07d81f2bcd73"))).isEqualTo(1);
            assertThat(orderMapper.insert(order(
                    secondOrderId, 23L, "fa9de8da-4d8e-4a0a-b94d-cc9f82917af0"))).isEqualTo(1);

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
    void callbackAndPaidStateBatchesPersistMultipleOrdersTogether() {
        byte[] firstOrderId = id((byte) 31);
        byte[] secondOrderId = id((byte) 32);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            MembershipOrderMapper orderMapper = session.getMapper(MembershipOrderMapper.class);
            MembershipPaymentCallbackMapper callbackMapper =
                    session.getMapper(MembershipPaymentCallbackMapper.class);
            assertThat(orderMapper.insert(order(
                    firstOrderId, 17L, "671d861b-1370-41cc-a35d-685960b0ada1"))).isEqualTo(1);
            assertThat(orderMapper.insert(order(
                    secondOrderId, 17L, "7cc48645-7ad8-4242-8e2a-b06f44c8e3c7"))).isEqualTo(1);

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
                    VALUES (17), (23)
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
