package com.example.temperate.mapper.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.auth.enums.RegistrationSource;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.entity.UserProfile;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
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
 * 用真实 PostgreSQL 验证毫秒边界模板采用批量写入，并且清理只删除本轮订单与回调而保留用户模板。
 */
@Testcontainers(disabledWithoutDocker = true)
final class MembershipPaymentBoundaryFixtureMapperIntegrationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final long FIRST_USER_ID = 70_000_000_000_000_000L;
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 23, 12, 0, 0, 0, ZoneOffset.UTC);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("membership_boundary_fixture_test")
            .withUsername("membership_boundary_fixture_test")
            .withPassword("membership_boundary_fixture_test_password");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void configure() throws Exception {
        applySchema();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @Test
    void shouldBatchCreateValidateResetAndRetainTemplatesWhileDeletingOnlyRunOrders() {
        long first = FIRST_USER_ID;
        long second = FIRST_USER_ID + 1L;
        byte[] firstOrderId = id((byte) 91);
        byte[] secondOrderId = id((byte) 92);
        byte[] callbackId = id((byte) 93);

        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            UserLoginIdentityMapper identityMapper = session.getMapper(UserLoginIdentityMapper.class);
            UserProfileMapper profileMapper = session.getMapper(UserProfileMapper.class);
            UserMembershipQuotaMapper quotaMapper = session.getMapper(UserMembershipQuotaMapper.class);
            MembershipOrderMapper orderMapper = session.getMapper(MembershipOrderMapper.class);
            MembershipPaymentCallbackMapper callbackMapper =
                    session.getMapper(MembershipPaymentCallbackMapper.class);

            assertThat(identityMapper.batchInsertBoundaryFixtures(List.of(
                            identity(first), identity(second))))
                    .isEqualTo(2);
            assertThat(profileMapper.batchInsertBoundaryFixtures(List.of(
                            profile(first), profile(second))))
                    .isEqualTo(2);
            assertThat(quotaMapper.batchInsertBoundaryFixtures(List.of(
                            quota(first), quota(second))))
                    .isEqualTo(2);

            assertThat(identityMapper.findByIds(List.of(first, second)))
                    .extracting(UserLoginIdentity::getEmail)
                    .containsExactly("boundary-0000@example.invalid", "boundary-0001@example.invalid");
            assertThat(profileMapper.findByLoginIdentityIds(List.of(first, second)))
                    .extracting(UserProfile::getLoginIdentityId)
                    .containsExactly(first, second);
            assertThat(quotaMapper.findByLoginIdentityIds(List.of(first, second)))
                    .extracting(UserMembershipQuota::getMembershipTier)
                    .containsOnly(MembershipTier.FREE.ordinal());

            assertThat(orderMapper.insert(order(firstOrderId, first, "00000000-0000-4000-8000-000000000091")))
                    .isEqualTo(1);
            assertThat(orderMapper.insert(order(secondOrderId, second, "00000000-0000-4000-8000-000000000092")))
                    .isEqualTo(1);
            assertThat(callbackMapper.batchInsertOrResolve("""
                    [{"ordinal":0,"idHex":"%s","orderIdHex":"%s",
                      "providerTradeNo":"boundary-provider-91","tradeStatus":"TRADE_SUCCESS",
                      "paidAmountYuan":0.05,"paidAt":"2026-08-23T12:00:01Z",
                      "receivedAt":"2026-08-23T12:00:02Z"}]
                    """.formatted(hex(callbackId), hex(firstOrderId)))).hasSize(1);

            assertThat(orderMapper.countByLoginIdentityIdRange(first, second + 1L)).isEqualTo(2);
            assertThat(callbackMapper.countByLoginIdentityIdRange(first, second + 1L)).isEqualTo(1);
            String firstOrderIds = "[\"" + hex(firstOrderId) + "\"]";
            assertThat(callbackMapper.deleteByOrderIdsJson(firstOrderIds)).isEqualTo(1);
            assertThat(orderMapper.deleteByIdsJson(firstOrderIds)).isEqualTo(1);

            assertThat(orderMapper.findById(firstOrderId)).isNull();
            assertThat(orderMapper.findById(secondOrderId)).isNotNull();
            assertThat(identityMapper.findByIds(List.of(first, second))).hasSize(2);
            assertThat(profileMapper.findByLoginIdentityIds(List.of(first, second))).hasSize(2);
            assertThat(quotaMapper.findByLoginIdentityIds(List.of(first, second))).hasSize(2);
            session.rollback();
        }
    }

    private static UserLoginIdentity identity(long id) {
        UserLoginIdentity identity = new UserLoginIdentity();
        identity.setId(id);
        identity.setRegistrationSource(RegistrationSource.STANDARD);
        identity.setEmail("boundary-%04d@example.invalid".formatted(id - FIRST_USER_ID));
        identity.setEmailVerified(false);
        return identity;
    }

    private static UserProfile profile(long userId) {
        UserProfile profile = new UserProfile();
        profile.setLoginIdentityId(userId);
        profile.setDisplayName("Boundary " + (userId - FIRST_USER_ID));
        profile.setAccountStatus(0);
        return profile;
    }

    private static UserMembershipQuota quota(long userId) {
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setLoginIdentityId(userId);
        quota.setMembershipTier(MembershipTier.FREE.ordinal());
        quota.setQuotaBalanceMinor(5_000L);
        quota.setQuotaPeriodEndsAt(NOW);
        return quota;
    }

    private static MembershipOrder order(byte[] orderId, long userId, String idempotencyKey) {
        MembershipOrder order = new MembershipOrder();
        order.setId(orderId);
        order.setLoginIdentityId(userId);
        order.setMembershipTier(MembershipTier.GO);
        order.setPayAmountYuan(new BigDecimal("0.05"));
        order.setPayType("alipay");
        order.setStatus(MembershipOrderStatus.PENDING_PAYMENT);
        order.setIdempotencyKey(UUID.fromString(idempotencyKey));
        order.setProviderTradeNo("boundary-provider-" + (userId - FIRST_USER_ID + 91L));
        order.setPaymentStartedAt(NOW);
        order.setExpiresAt(NOW.plusMinutes(5));
        order.setStateVersion(1L);
        order.setCreatedAt(NOW);
        order.setUpdatedAt(NOW);
        return order;
    }

    private static byte[] id(byte value) {
        byte[] id = new byte[16];
        Arrays.fill(id, value);
        return id;
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static void applySchema() throws IOException, SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            for (String script : List.of(
                    "sql/001_create_users.sql",
                    "sql/002_create_user_profile.sql",
                    "sql/005_create_user_membership_quota.sql",
                    "sql/018_create_membership_order.sql",
                    "sql/019_create_membership_payment_callback.sql",
                    "sql/migrations/029_create_membership_payment_readable_views.sql",
                    "sql/migrations/030_add_membership_order_entitlement_resolution.sql",
                    "sql/migrations/032_add_membership_order_not_granted_resolution.sql")) {
                statement.execute(read(script));
            }
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory() throws IOException {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                POSTGRES.getDriverClassName(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Configuration configuration = new Configuration(new Environment(
                "membership-boundary-fixture-test", new JdbcTransactionFactory(), dataSource));
        for (String resource : List.of(
                "mapper/user/identity/UserLoginIdentityMapper.xml",
                "mapper/user/profile/UserProfileMapper.xml",
                "mapper/user/membership/UserMembershipQuotaMapper.xml",
                "mapper/user/membership/payment/MembershipOrderMapper.xml",
                "mapper/user/membership/payment/MembershipPaymentCallbackMapper.xml")) {
            addMapper(configuration, resource);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void addMapper(Configuration configuration, String resource) throws IOException {
        try (InputStream inputStream = MembershipPaymentBoundaryFixtureMapperIntegrationTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(inputStream, "Missing mapper XML: " + resource);
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private static Connection openConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
