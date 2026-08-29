package com.example.temperate.service.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.common.redis.key.PaymentCallbackRedisId;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipPaymentCallbackMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.MembershipOrderEntitlementResolution;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackBatchService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackFingerprintService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackPersistenceService;
import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackReceiveService;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRefundService;
import com.example.temperate.service.user.membership.payment.callback.MembershipPaymentRejectedCallbackResumeService;
import com.example.temperate.service.user.membership.payment.callback.PaymentFactReconciliationService;
import com.example.temperate.service.user.membership.payment.callback.SimulatedLiuhaoCallbackCommand;
import com.example.temperate.service.user.membership.payment.callback.SimulatedLiuhaoCallbackResult;
import com.example.temperate.service.user.membership.payment.callback.impl.PaymentCallbackBatchServiceImpl;
import com.example.temperate.service.user.membership.payment.callback.impl.MembershipPaymentCallbackDecisionServiceImpl;
import com.example.temperate.service.user.membership.payment.callback.impl.PaymentCallbackFingerprintServiceImpl;
import com.example.temperate.service.user.membership.payment.callback.impl.PaymentCallbackPersistenceServiceImpl;
import com.example.temperate.service.user.membership.payment.callback.impl.PaymentCallbackReceiveServiceImpl;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.entitlement.MembershipPaymentEntitlementSettlementService;
import com.example.temperate.service.user.membership.payment.entitlement.impl.MembershipPaymentEntitlementSettlementServiceImpl;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestFaultGate;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentOrderLookupService;
import com.example.temperate.service.user.membership.payment.order.impl.MembershipPaymentOrderLookupServiceImpl;
import com.example.temperate.service.user.membership.payment.persistence.MembershipOrderBatchPersistenceService;
import com.example.temperate.service.user.membership.payment.persistence.MembershipOrderPersistenceService;
import com.example.temperate.service.user.membership.payment.persistence.impl.MembershipOrderBatchPersistenceServiceImpl;
import com.example.temperate.service.user.membership.payment.persistence.impl.MembershipOrderPersistenceServiceImpl;
import com.example.temperate.service.user.membership.payment.store.MembershipPaymentUnappliedCallbackStore;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteCoordinator;
import com.example.temperate.service.user.membership.payment.store.MembershipOrderSnapshotWriteRuntimeSnapshot;
import com.example.temperate.service.user.membership.payment.provider.MembershipPaymentProviderRegistry;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentFinalCheckScheduler;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckMessage;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitEnvelope;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.rabbit.impl.MembershipPaymentCheckConsumerServiceImpl;
import com.example.temperate.service.user.membership.payment.store.impl.RedisMembershipOrderSnapshotStore;
import com.example.temperate.service.user.membership.payment.store.impl.RedisOrderPersistenceQueue;
import com.example.temperate.service.user.membership.payment.store.impl.RedisPaymentCallbackQueue;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 该联合集成测试是来贯通 Rabbit 最终检查、回调批处理和订单批量刷盘，并验证 PostgreSQL 提交后终态才退出 Redis。
 */
@Testcontainers(disabledWithoutDocker = true)
final class MembershipPaymentPipelineIntegrationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RedisKeyFactory KEYS = new RedisKeyFactory("test");
    private static final HybridBase64UrlCodec Base64URL = new HybridBase64UrlCodec();
    private static final byte[] ORDER_BYTES = bytes((byte) 1);
    private static final byte[] CALLBACK_BYTES = bytes((byte) 2);
    private static final String ORDER_ID = Base64URL.encode(ORDER_BYTES);

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault(
                    "AIT_TEST_REDIS_IMAGE", "redis:7.4.2-alpine")))
            .withExposedPorts(6379);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("membership_payment_pipeline_test")
            .withUsername("membership_payment_pipeline_test")
            .withPassword("membership_payment_pipeline_test_password");

    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private MembershipOrderMapper orderMapper;
    private RedisMembershipOrderSnapshotStore orderStore;
    private RedisPaymentCallbackQueue callbackQueue;
    private RedisOrderPersistenceQueue orderPersistenceQueue;
    private MembershipPaymentProperties properties;
    private MembershipPaymentMetrics metrics;

    @BeforeAll
    static void connectInfrastructure() throws Exception {
        redisConnectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisConnectionFactory.start();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
        applyMigrations();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @AfterAll
    static void disconnectInfrastructure() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        sqlSession = sqlSessionFactory.openSession(true);
        try (Statement statement = sqlSession.getConnection().createStatement()) {
            statement.execute("TRUNCATE membership_payment_callback, membership_order");
        }
        orderMapper = sqlSession.getMapper(MembershipOrderMapper.class);
        orderStore = new RedisMembershipOrderSnapshotStore(redisTemplate, KEYS);
        callbackQueue = new RedisPaymentCallbackQueue(redisTemplate, KEYS);
        orderPersistenceQueue = new RedisOrderPersistenceQueue(redisTemplate, KEYS);
        properties = properties();
        metrics = mock(MembershipPaymentMetrics.class);
    }

    @AfterEach
    void closeSession() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    void callbackMarkerStopsRabbitTimelineThenWorkerGrantsAndPersistsPaid()
            throws Exception {
        MembershipOrder databaseOrder = order();
        assertThat(orderMapper.insert(databaseOrder)).isEqualTo(1);
        orderStore.put(snapshot(databaseOrder));

        PaymentCallbackReceiveService receiveService = receiveService();
        SimulatedLiuhaoCallbackResult received = receiveService.receive(callbackCommand());
        String callbackId = received.callbackId();
        assertThat(redisTemplate.opsForZSet().zCard(KEYS.paymentCallbackReadyKey()))
                .isEqualTo(1L);

        assertThat(redisTemplate.hasKey(KEYS.paymentCallbackDataKey(
                new PaymentCallbackRedisId(callbackId)))).isTrue();

        MembershipPaymentFinalCheckScheduler finalCheckScheduler =
                mock(MembershipPaymentFinalCheckScheduler.class);
        MembershipPaymentProviderRegistry providerRegistry =
                mock(MembershipPaymentProviderRegistry.class);
        PaymentFactReconciliationService reconciliationService = (order, fact) ->
                fact.callbackId() != null
                        && callbackQueue.ensureReady(fact.callbackId(), CLOCK.millis());
        new MembershipPaymentCheckConsumerServiceImpl(
                realLookupService(),
                orderStore,
                providerRegistry,
                reconciliationService,
                mock(MembershipPaymentCheckPublisher.class),
                finalCheckScheduler,
                properties,
                CLOCK,
                metrics)
                .process(paymentEnvelope(8));

        verify(providerRegistry, never()).getRequired(any());
        verify(finalCheckScheduler, never()).scheduleClosing(
                any(), any(), anyInt());
        assertThat(redisTemplate.opsForZSet().zCard(KEYS.paymentCallbackReadyKey()))
                .isEqualTo(1L);
        assertThat(orderStore.find(ORDER_ID)).get()
                .satisfies(value -> {
                    assertThat(value.status())
                            .isEqualTo(MembershipOrderStatus.PENDING_PAYMENT);
                    assertThat(value.stateVersion()).isEqualTo(1L);
                });
        assertThat(orderPersistenceQueue.dirtySize()).isZero();

        realCallbackBatchService().flushOneRun();

        assertThat(callbackRowCount()).isEqualTo(1L);
        MembershipOrder granted = orderMapper.findById(ORDER_BYTES);
        assertThat(granted.getStatus()).isEqualTo(MembershipOrderStatus.PAID);
        assertThat(granted.getEntitlementResolution())
                .isEqualTo(MembershipOrderEntitlementResolution.APPLIED);
        assertThat(sqlSession.getMapper(UserMembershipQuotaMapper.class)
                        .findByLoginIdentityId(17L))
                .satisfies(quota -> {
                    assertThat(quota.getMembershipTier())
                            .isEqualTo(MembershipTier.PLUS.ordinal());
                    assertThat(quota.getQuotaBalanceMinor()).isEqualTo(200_000L);
                    assertThat(quota.getQuotaPeriodStartedAt()).isNull();
                    assertThat(quota.getQuotaPeriodEndsAt())
                            .isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
                    assertThat(quota.getMembershipExpiresAt())
                            .isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
                                    .plusMonths(1));
                });
        assertThat(orderStore.find(ORDER_ID)).get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(MembershipOrderStatus.PAID);
                    assertThat(value.stateVersion()).isEqualTo(2L);
                    assertThat(value.providerTradeNo()).isEqualTo("provider-trade-1");
                });
        assertThat(orderPersistenceQueue.dirtySize()).isEqualTo(1L);
        assertThat(callbackQueue.processingSize()).isZero();
        assertThat(redisTemplate.opsForZSet().zCard(KEYS.paymentCallbackReadyKey()))
                .isZero();
        assertThat(redisTemplate.hasKey(KEYS.paymentCallbackDataKey(
                new PaymentCallbackRedisId(callbackId)))).isFalse();

        RLock lock = mock(RLock.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getLock(KEYS.orderPersistenceLockKey())).thenReturn(lock);
        when(lock.tryLock(100L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        MembershipOrderBatchPersistenceService orderBatchService =
                new MembershipOrderBatchPersistenceServiceImpl(
                        redissonClient,
                        KEYS,
                        orderPersistenceQueue,
                        orderStore,
                        realOrderPersistenceService(),
                        properties,
                        CLOCK,
                        metrics);

        orderBatchService.flushOneRun();

        MembershipOrder persisted = orderMapper.findById(ORDER_BYTES);
        assertThat(persisted.getStatus()).isEqualTo(MembershipOrderStatus.PAID);
        assertThat(persisted.getStateVersion()).isEqualTo(2L);
        assertThat(persisted.getProviderTradeNo()).isEqualTo("provider-trade-1");
        assertThat(orderPersistenceQueue.dirtySize()).isZero();
        assertThat(orderPersistenceQueue.processingSize()).isZero();
        assertThat(orderStore.find(ORDER_ID)).isEmpty();
        verify(lock).unlock();
    }

    private PaymentCallbackReceiveService receiveService() {
        HybridSemaphoreIdWorker idWorker = mock(HybridSemaphoreIdWorker.class);
        when(idWorker.nextId()).thenReturn(Arrays.copyOf(
                CALLBACK_BYTES, CALLBACK_BYTES.length));
        PaymentCallbackFingerprintService fingerprintService =
                new PaymentCallbackFingerprintServiceImpl(properties);
        return new PaymentCallbackReceiveServiceImpl(
                callbackQueue,
                fingerprintService,
                idWorker,
                Base64URL,
                properties,
                CLOCK,
                metrics);
    }

    private PaymentCallbackBatchService realCallbackBatchService() {
        ObjectMapper objectMapper = objectMapper();
        return new PaymentCallbackBatchServiceImpl(
                callbackQueue,
                orderStore,
                mock(MembershipPaymentUnappliedCallbackStore.class),
                orderMapper,
                realCallbackPersistenceService(),
                realEntitlementSettlementService(),
                new MembershipPaymentCallbackDecisionServiceImpl(properties),
                mock(MembershipPaymentRefundService.class),
                mock(MembershipPaymentRejectedCallbackResumeService.class),
                mock(MembershipPaymentLoadtestFaultGate.class),
                Base64URL,
                objectMapper,
                properties,
                CLOCK,
                metrics);
    }

    private PaymentCallbackPersistenceService realCallbackPersistenceService() {
        MembershipPaymentCallbackMapper callbackMapper =
                sqlSession.getMapper(MembershipPaymentCallbackMapper.class);
        return new PaymentCallbackPersistenceServiceImpl(
                callbackMapper,
                Base64URL,
                objectMapper());
    }

    private MembershipPaymentEntitlementSettlementService
            realEntitlementSettlementService() {
        return new MembershipPaymentEntitlementSettlementServiceImpl(
                orderMapper,
                sqlSession.getMapper(MembershipPaymentCallbackMapper.class),
                sqlSession.getMapper(UserMembershipQuotaMapper.class),
                tier -> new MembershipQuotaPlan(
                        tier == MembershipTier.PLUS ? 200_000L : 5_000L,
                        Duration.ofDays(7)),
                mock(UserProfileCacheInvalidationExecutor.class),
                Base64URL,
                objectMapper());
    }

    private MembershipOrderPersistenceService realOrderPersistenceService() {
        return new MembershipOrderPersistenceServiceImpl(
                orderMapper,
                Base64URL,
                objectMapper());
    }

    private static ObjectMapper objectMapper() {
        // Spring Boot 默认输出 ISO-8601；手工装配测试也必须复现同一边界，避免把时间写成 PostgreSQL 无法解析的 epoch 小数。
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private MembershipPaymentOrderLookupService realLookupService() {
        MembershipOrderSnapshotWriteCoordinator coordinator =
                new MembershipOrderSnapshotWriteCoordinator() {
                    @Override
                    public MembershipOrderSnapshot putAndGet(MembershipOrderSnapshot snapshot) {
                        return orderStore.putAndGet(snapshot);
                    }

                    @Override
                    public MembershipOrderSnapshot patchPaymentAttempt(
                            MembershipOrderSnapshot databaseSnapshot) {
                        return orderStore.putAndGet(databaseSnapshot);
                    }

                    @Override
                    public MembershipOrderSnapshotWriteRuntimeSnapshot runtimeSnapshot() {
                        return new MembershipOrderSnapshotWriteRuntimeSnapshot(
                                        true, 128, 2, 256, 0, 256, java.util.List.of(0, 0));
                    }
                };
        return new MembershipPaymentOrderLookupServiceImpl(
                orderStore, coordinator, orderMapper, Base64URL);
    }

    private long callbackRowCount() throws SQLException {
        try (PreparedStatement statement = sqlSession.getConnection().prepareStatement(
                        "SELECT COUNT(*) FROM membership_payment_callback");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static MembershipOrder order() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrder order = new MembershipOrder();
        order.setId(Arrays.copyOf(ORDER_BYTES, ORDER_BYTES.length));
        order.setLoginIdentityId(17L);
        order.setMembershipTier(MembershipTier.PLUS);
        order.setPayAmountYuan(new BigDecimal("20.00"));
        order.setPayType("alipay");
        order.setStatus(MembershipOrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(UUID.fromString(
                "550e8400-e29b-41d4-a716-446655440000"));
        order.setPaymentStartedAt(now);
        order.setExpiresAt(now.plusMinutes(5));
        order.setStateVersion(1L);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    private static MembershipOrderSnapshot snapshot(MembershipOrder order) {
        return new MembershipOrderSnapshot(
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
                ORDER_ID,
                order.getLoginIdentityId(),
                order.getMembershipTier(),
                order.getPayAmountYuan(),
                order.getPayType(),
                order.getStatus(),
                order.getIdempotencyKey(),
                order.getProviderTradeNo(),
                order.getPaymentStartedAt(),
                order.getExpiresAt(),
                order.getClosingDeadlineAt(),
                order.getPaidAt(),
                order.getStateVersion(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    private static SimulatedLiuhaoCallbackCommand callbackCommand() {
        return new SimulatedLiuhaoCallbackCommand(
                "merchant-test",
                "provider-trade-1",
                ORDER_ID,
                "channel-trade-1",
                "alipay",
                "TRADE_SUCCESS",
                "2026-08-20 12:00:00",
                "2026-08-20 12:00:00",
                "PLUS membership",
                "20.00",
                "",
                "",
                Long.toString(NOW.getEpochSecond()),
                "simulated-signature",
                "RSA");
    }

    private static MembershipPaymentRabbitEnvelope<MembershipPaymentCheckMessage>
            paymentEnvelope(int stage) {
        return new MembershipPaymentRabbitEnvelope<>(
                Base64URL.encode(bytes((byte) 3)),
                MembershipPaymentRabbitNames.PAYMENT_EVENT,
                MembershipPaymentRabbitEnvelope.CURRENT_SCHEMA_VERSION,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                "trace-pipeline-test",
                new MembershipPaymentCheckMessage(ORDER_ID, stage));
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        true,
                        "merchant-test",
                        "0123456789abcdef0123456789abcdef",
                        Duration.ofMinutes(5),
                        16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(10),
                        Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L,
                        100,
                        20,
                        Duration.ofSeconds(60),
                        Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(
                                10_000L,
                                10_000L,
                                10_000L,
                                15_000L,
                                15_000L,
                                30_000L,
                                30_000L,
                                60_000L,
                                120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30),
                        3));
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
                    VALUES (17)
                    """);
            statement.execute(read("sql/005_create_user_membership_quota.sql"));
            statement.execute("""
                    INSERT INTO user_membership_quota (
                        login_identity_id,
                        membership_tier,
                        quota_balance_minor,
                        quota_period_started_at,
                        quota_period_ends_at,
                        membership_expires_at
                    )
                    VALUES (17, 0, 5000, NULL, '2026-08-20T12:00:00Z', NULL)
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
                "membership-payment-pipeline-test",
                new JdbcTransactionFactory(),
                dataSource);
        Configuration configuration = new Configuration(environment);
        addMapper(configuration, "mapper/user/membership/payment/MembershipOrderMapper.xml");
        addMapper(configuration, "mapper/user/membership/UserMembershipQuotaMapper.xml");
        addMapper(
                configuration,
                "mapper/user/membership/payment/MembershipPaymentCallbackMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void addMapper(Configuration configuration, String resource)
            throws IOException {
        try (InputStream inputStream = MembershipPaymentPipelineIntegrationTest.class
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
                    && Files.isDirectory(current.resolve("ai-temperate-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }

    private static byte[] bytes(byte value) {
        byte[] result = new byte[16];
        Arrays.fill(result, value);
        return result;
    }
}
