package com.example.temperate.mapper.user.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.entity.UserProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
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

@Testcontainers(disabledWithoutDocker = true)
/**
 * 在 PostgreSQL 环境中验证用户登录身份 Mapper 的查询和写入语义。
 */
class PostgreSqlMapperIntegrationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("ai_temperate_mapper_test")
            .withUsername("mapper_test")
            .withPassword("mapper_test_password");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void configureDatabaseAndMyBatis() throws IOException, SQLException {
        applyMigrations();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @Test
    void migrationsAndMapperContractsWorkAgainstRealPostgreSql() throws Exception {
        assertUniqueContactIndexes();

        UserLoginIdentity first = identity(
                10001L,
                "first@example.com",
                "+15550000001",
                "{bcrypt}integration-first");
        UserLoginIdentity second = identity(
                10002L,
                "second@example.com",
                "+15550000002",
                "{bcrypt}integration-second");

        assertEquals(1, insertIdentity(first));
        assertEquals(1, insertIdentity(second));

        UserLoginIdentity byEmail = findByEmail("first@example.com");
        assertIdentityRoundTrip(first, byEmail);
        OffsetDateTime initialUpdatedAt = byEmail.getUpdatedAt();

        UserLoginIdentity byPhone = findByPhone("+15550000001");
        assertEquals(first.getId(), byPhone.getId());
        assertEquals(first.getEmail(), byPhone.getEmail());

        List<UserLoginIdentity> conflicts =
                findConflicts("first@example.com", "+15550000002");
        assertEquals(Set.of(first.getId(), second.getId()), idsOf(conflicts));
        assertTrue(conflicts.stream().allMatch(identity -> identity.getPasswordHash() == null));

        waitForDatabaseTimestampTick();
        assertEquals(
                1,
                updatePassword(
                        first.getId(),
                        "{bcrypt}integration-upgraded"));
        assertEquals(
                0,
                updatePassword(
                        999_999L,
                        "{bcrypt}integration-missing"));

        UserLoginIdentity upgraded = findByEmail("first@example.com");
        assertEquals("{bcrypt}integration-upgraded", upgraded.getPasswordHash());
        assertEquals(1L, upgraded.getPasswordVersion());
        assertTrue(upgraded.getUpdatedAt().isAfter(initialUpdatedAt));

        waitForDatabaseTimestampTick();
        assertEquals(
                1,
                updatePasswordAndIncrementVersion(
                        first.getId(),
                        "{bcrypt}integration-reset"));
        assertEquals(
                0,
                updatePasswordAndIncrementVersion(
                        999_999L,
                        "{bcrypt}integration-reset-missing"));

        UserLoginIdentity reset = findByEmail("first@example.com");
        assertEquals("{bcrypt}integration-reset", reset.getPasswordHash());
        assertEquals(2L, reset.getPasswordVersion());
        assertTrue(reset.getUpdatedAt().isAfter(upgraded.getUpdatedAt()));

        UserProfile validProfile = profile(first.getId(), "Temperate User");
        assertEquals(1, insertProfile(validProfile));
        assertNotNull(validProfile.getId());
        assertTrue(validProfile.getId() > 0);
        assertEquals("Temperate User", findProfileDisplayName(validProfile.getId()));

        UserMembershipQuota validMembershipQuota = membershipQuota(first.getId());
        assertEquals(1, insertMembershipQuota(validMembershipQuota));
        assertNotNull(validMembershipQuota.getId());
        UserMembershipQuota storedMembershipQuota =
                findMembershipQuota(first.getId());
        assertEquals(0, storedMembershipQuota.getMembershipTier());
        assertEquals(5000L, storedMembershipQuota.getQuotaBalanceMinor());

        assertUniqueViolation(() -> insertMembershipQuota(membershipQuota(first.getId())));
        SQLException negativeBalance = assertThrows(
                SQLException.class,
                () -> insertMembershipQuotaWithBalance(777_777L, -1L));
        assertEquals("23514", negativeBalance.getSQLState());

        assertUniqueContactValuesAreEnforced();

        UserProfile orphanProfile = profile(888_888L, "Orphan User");
        assertEquals(1, insertProfile(orphanProfile));
        assertNotNull(orphanProfile.getId());

        Set<Long> orphanIds = executeOrphanCheck();
        assertTrue(orphanIds.contains(orphanProfile.getId()));
        assertFalse(orphanIds.contains(validProfile.getId()));

        UserMembershipQuota orphanMembershipQuota = membershipQuota(888_889L);
        assertEquals(1, insertMembershipQuota(orphanMembershipQuota));
        Set<Long> membershipOrphanIds = executeMembershipQuotaOrphanCheck();
        assertTrue(membershipOrphanIds.contains(orphanMembershipQuota.getId()));
        assertFalse(membershipOrphanIds.contains(validMembershipQuota.getId()));
    }

    private static void assertUniqueContactIndexes() throws SQLException {
        String query = """
                SELECT indexname, indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'userloginidentity'
                  AND indexname IN (
                      'uk_userloginidentity_email_lower',
                      'uk_userloginidentity_phone'
                  )
                """;
        Map<String, String> indexes = new HashMap<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                indexes.put(resultSet.getString("indexname"), resultSet.getString("indexdef"));
            }
        }

        assertEquals(
                Set.of(
                        "uk_userloginidentity_email_lower",
                        "uk_userloginidentity_phone"),
                indexes.keySet(),
                "sql/001-003 must create both contact indexes");
        String emailIndex = indexes.get("uk_userloginidentity_email_lower").toLowerCase();
        assertTrue(emailIndex.contains("unique index"));
        assertTrue(emailIndex.contains("lower"));
        assertTrue(emailIndex.contains("email"));

        String phoneIndex = indexes.get("uk_userloginidentity_phone").toLowerCase();
        assertTrue(phoneIndex.contains("unique index"));
        assertTrue(phoneIndex.contains("phone"));
        assertTrue(phoneIndex.contains("where"));
        assertTrue(phoneIndex.contains("is not null"));
    }

    private static void assertUniqueContactValuesAreEnforced() {
        UserLoginIdentity duplicateEmail = identity(
                10003L,
                "FIRST@EXAMPLE.COM",
                "+15550000003",
                "{bcrypt}duplicate-email");
        assertUniqueViolation(() -> insertIdentity(duplicateEmail));

        UserLoginIdentity duplicatePhone = identity(
                10004L,
                "third@example.com",
                "+15550000001",
                "{bcrypt}duplicate-phone");
        assertUniqueViolation(() -> insertIdentity(duplicatePhone));
    }

    private static void assertUniqueViolation(ThrowingOperation operation) {
        PersistenceException exception = assertThrows(PersistenceException.class, operation::run);
        SQLException sqlException = findSqlException(exception);
        assertNotNull(sqlException);
        assertEquals("23505", sqlException.getSQLState());
    }

    private static SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static void assertIdentityRoundTrip(
            UserLoginIdentity expected,
            UserLoginIdentity actual) {
        assertNotNull(actual);
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getEmail(), actual.getEmail());
        assertEquals(expected.getPhone(), actual.getPhone());
        assertEquals(expected.getPasswordHash(), actual.getPasswordHash());
        assertEquals(1L, actual.getPasswordVersion());
        assertNotNull(actual.getCreatedAt());
        assertNotNull(actual.getUpdatedAt());
    }

    private static int insertIdentity(UserLoginIdentity identity) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(UserLoginIdentityMapper.class).insert(identity);
        }
    }

    private static UserLoginIdentity findByEmail(String normalizedEmail) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(UserLoginIdentityMapper.class)
                    .findByNormalizedEmail(normalizedEmail);
        }
    }

    private static UserLoginIdentity findByPhone(String normalizedPhone) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(UserLoginIdentityMapper.class)
                    .findByNormalizedPhone(normalizedPhone);
        }
    }

    private static List<UserLoginIdentity> findConflicts(
            String normalizedEmail, String normalizedPhone) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(UserLoginIdentityMapper.class)
                    .findConflicts(normalizedEmail, normalizedPhone);
        }
    }

    private static int updatePassword(Long id, String passwordHash) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(UserLoginIdentityMapper.class)
                    .updatePasswordHash(id, passwordHash);
        }
    }

    private static int updatePasswordAndIncrementVersion(Long id, String passwordHash) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(UserLoginIdentityMapper.class)
                    .updatePasswordHashAndIncrementVersion(id, passwordHash);
        }
    }

    private static void waitForDatabaseTimestampTick() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_sleep(0.01)");
        }
    }

    private static int insertProfile(UserProfile profile) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(UserProfileMapper.class).insert(profile);
        }
    }

    private static int insertMembershipQuota(UserMembershipQuota membershipQuota) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(UserMembershipQuotaMapper.class)
                    .insert(membershipQuota);
        }
    }

    private static UserMembershipQuota findMembershipQuota(long loginIdentityId) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(UserMembershipQuotaMapper.class)
                    .findByLoginIdentityId(loginIdentityId);
        }
    }

    private static void insertMembershipQuotaWithBalance(
            long loginIdentityId, long quotaBalanceMinor) throws SQLException {
        String sql = """
                INSERT INTO user_membership_quota (
                    login_identity_id,
                    quota_balance_minor
                )
                VALUES (?, ?)
                """;
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, loginIdentityId);
            statement.setLong(2, quotaBalanceMinor);
            statement.executeUpdate();
        }
    }

    private static String findProfileDisplayName(Long profileId) throws SQLException {
        String query = "SELECT display_name FROM user_profile WHERE id = ?";
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString("display_name");
            }
        }
    }

    private static Set<Long> executeOrphanCheck() throws IOException, SQLException {
        String sql = Files.readString(
                PROJECT_ROOT.resolve("sql/checks/user_profile_orphans.sql"),
                StandardCharsets.UTF_8);
        Set<Long> orphanIds = new HashSet<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                orphanIds.add(resultSet.getLong("user_profile_id"));
            }
        }
        return orphanIds;
    }

    private static Set<Long> executeMembershipQuotaOrphanCheck()
            throws IOException, SQLException {
        String sql = Files.readString(
                PROJECT_ROOT.resolve("sql/checks/user_membership_quota_orphans.sql"),
                StandardCharsets.UTF_8);
        Set<Long> orphanIds = new HashSet<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                orphanIds.add(resultSet.getLong("user_membership_quota_id"));
            }
        }
        return orphanIds;
    }

    private static Connection openConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private static void applyMigrations() throws IOException, SQLException {
        List<String> migrations = List.of(
                "sql/001_create_users.sql",
                "sql/002_create_user_profile.sql",
                "sql/003_create_ai_model.sql",
                "sql/005_create_user_membership_quota.sql");
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            for (String migration : migrations) {
                String sql = Files.readString(
                        PROJECT_ROOT.resolve(migration),
                        StandardCharsets.UTF_8);
                statement.execute(sql);
            }
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory() throws IOException {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                POSTGRES.getDriverClassName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Environment environment =
                new Environment("testcontainers", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        parseMapper(configuration, "mapper/user/identity/UserLoginIdentityMapper.xml");
        parseMapper(configuration, "mapper/user/profile/UserProfileMapper.xml");
        parseMapper(configuration, "mapper/user/membership/UserMembershipQuotaMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void parseMapper(Configuration configuration, String resource)
            throws IOException {
        try (InputStream inputStream =
                PostgreSqlMapperIntegrationTest.class.getClassLoader()
                        .getResourceAsStream(resource)) {
            assertNotNull(inputStream, () -> "Missing mapper XML: " + resource);
            XMLMapperBuilder builder = new XMLMapperBuilder(
                    inputStream,
                    configuration,
                    resource,
                    configuration.getSqlFragments());
            builder.parse();
        }
    }

    private static UserLoginIdentity identity(
            long id,
            String email,
            String phone,
            String passwordHash) {
        UserLoginIdentity identity = new UserLoginIdentity();
        identity.setId(id);
        identity.setEmail(email);
        identity.setPhone(phone);
        identity.setPasswordHash(passwordHash);
        return identity;
    }

    private static UserProfile profile(long loginIdentityId, String displayName) {
        UserProfile profile = new UserProfile();
        profile.setLoginIdentityId(loginIdentityId);
        profile.setDisplayName(displayName);
        profile.setAccountStatus(0);
        return profile;
    }

    private static UserMembershipQuota membershipQuota(long loginIdentityId) {
        UserMembershipQuota membershipQuota = new UserMembershipQuota();
        membershipQuota.setLoginIdentityId(loginIdentityId);
        return membershipQuota;
    }

    private static Set<Long> idsOf(List<UserLoginIdentity> identities) {
        Set<Long> ids = new HashSet<>();
        for (UserLoginIdentity identity : identities) {
            ids.add(identity.getId());
        }
        return ids;
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

    @FunctionalInterface
    private interface ThrowingOperation {

        void run();
    }
}
