package com.example.temperate.service.registration.service.lifecycle.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.model.user.entity.UserProfile;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.registration.component.executor.impl.SpringRegistrationAfterCommitExecutor;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.registration.component.observer.RegistrationCleanupObserver;
import com.example.temperate.service.auth.password.policy.PasswordStrengthPolicy;
import com.example.temperate.service.humanverification.HumanVerificationServiceRegistry;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import com.example.temperate.service.registration.dto.command.RegistrationCompleteCommand;
import com.example.temperate.service.registration.enums.RegistrationErrorCode;
import com.example.temperate.service.registration.exception.RegistrationException;
import com.example.temperate.service.registration.flow.domain.RegistrationCompletionClaim;
import com.example.temperate.service.registration.flow.domain.RegistrationFlowSnapshot;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;
import com.example.temperate.service.registration.flow.security.RegistrationTokenProtector;
import com.example.temperate.service.registration.flow.store.RegistrationFlowStore;
import com.example.temperate.service.registration.service.lifecycle.RegistrationService;
import com.example.temperate.service.registration.verification.delivery.coordinator.VerificationDeliveryCoordinator;
import com.example.temperate.service.registration.verification.delivery.operation.VerificationDeliveryOperationIdGenerator;
import com.example.temperate.service.registration.verification.service.registry.SixDigitVerificationCodeServiceRegistry;
import com.example.temperate.service.registration.verification.service.resolver.VerificationProviderResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 在 PostgreSQL 容器中验证注册开户事务原子性和唯一约束边界的集成测试。
 */
@Testcontainers(disabledWithoutDocker = true)
class RegistrationServicePostgreSqlTransactionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final Path PROJECT_ROOT = findProjectRoot();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    System.getenv().getOrDefault("AIT_TEST_POSTGRES_IMAGE", "postgres:15.13-alpine"))
            .withDatabaseName("registration_transaction_test")
            .withUsername("registration_test")
            .withPassword("registration_test_password");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepareDatabase() throws IOException, SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        applyMigrations();
        jdbc.execute("ALTER TABLE user_profile ALTER COLUMN display_name TYPE VARCHAR(1)");
    }

    @Test
    void downstreamInsertFailuresRollBackEarlierRegistrationWrites() {
        AtomicBoolean identityDefaultsVisibleBeforeProfileFailure = new AtomicBoolean();
        UserLoginIdentityMapper identityMapper = new JdbcIdentityMapper(jdbc);
        UserProfileMapper profileMapper = new JdbcProfileMapper(
                jdbc, identityDefaultsVisibleBeforeProfileFailure);
        UserMembershipQuotaMapper membershipQuotaMapper =
                mock(UserMembershipQuotaMapper.class);
        when(membershipQuotaMapper.insert(any())).thenReturn(1);
        RegistrationFlowStore flowStore = readyFlowStore();
        RegistrationCleanupObserver cleanupObserver = mock(RegistrationCleanupObserver.class);
        RegistrationServiceImpl target = service(
                identityMapper,
                profileMapper,
                membershipQuotaMapper,
                flowStore,
                cleanupObserver);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionProxyConfiguration.class);
            context.registerBean(
                    "transactionManager",
                    PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(RegistrationServiceImpl.class, () -> target);
            context.refresh();

            RegistrationService service = context.getBean(RegistrationService.class);
            assertThatThrownBy(() -> service.complete(command()))
                    .isInstanceOfSatisfying(
                            RegistrationException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(RegistrationErrorCode.AUTH_REGISTER_UNAVAILABLE));
        }

        assertThat(identityDefaultsVisibleBeforeProfileFailure).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM userloginidentity WHERE id = 42", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_profile", Integer.class))
                .isZero();
        verify(flowStore).releaseCompletionClaim(any(), any());

        jdbc.execute("ALTER TABLE user_profile ALTER COLUMN display_name TYPE VARCHAR(64)");
        AtomicBoolean identityDefaultsVisibleBeforeMembershipFailure = new AtomicBoolean();
        UserProfileMapper successfulProfileMapper = new JdbcProfileMapper(
                jdbc, identityDefaultsVisibleBeforeMembershipFailure);
        UserMembershipQuotaMapper failingMembershipQuotaMapper =
                mock(UserMembershipQuotaMapper.class);
        when(failingMembershipQuotaMapper.insert(any())).thenReturn(0);
        RegistrationFlowStore membershipFailureFlowStore = readyFlowStore();
        RegistrationServiceImpl membershipFailureTarget = service(
                new JdbcIdentityMapper(jdbc),
                successfulProfileMapper,
                failingMembershipQuotaMapper,
                membershipFailureFlowStore,
                mock(RegistrationCleanupObserver.class));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionProxyConfiguration.class);
            context.registerBean(
                    "transactionManager",
                    PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(RegistrationServiceImpl.class, () -> membershipFailureTarget);
            context.refresh();

            assertThatThrownBy(() -> context.getBean(RegistrationService.class).complete(command()))
                    .isInstanceOfSatisfying(
                            RegistrationException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(
                                            RegistrationErrorCode.REGISTRATION_PERSISTENCE_FAILED));
        }

        assertThat(identityDefaultsVisibleBeforeMembershipFailure).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM userloginidentity WHERE id = 42", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_profile", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_membership_quota", Integer.class))
                .isZero();
        verify(membershipFailureFlowStore).releaseCompletionClaim(any(), any());
    }

    private static RegistrationServiceImpl service(
            UserLoginIdentityMapper identityMapper,
            UserProfileMapper profileMapper,
            UserMembershipQuotaMapper membershipQuotaMapper,
            RegistrationFlowStore flowStore,
            RegistrationCleanupObserver cleanupObserver) {
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}integration-hash");
        HmacSha256Identifier hmac = new HmacSha256Identifier(
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8));
        return new RegistrationServiceImpl(
                identityMapper,
                profileMapper,
                membershipQuotaMapper,
                tier -> new com.example.temperate.service.user.membership.MembershipQuotaPlan(
                        5_000L, Duration.ofDays(7)),
                flowStore,
                new RegistrationInputNormalizer(),
                new PasswordStrengthPolicy(),
                new RegistrationTokenProtector(hmac, new AuthSessionSecretProtector(hmac)),
                new FixedTokenGenerator(),
                () -> "012345",
                new VerificationDeliveryOperationIdGenerator(),
                mock(VerificationDeliveryCoordinator.class),
                mock(SixDigitVerificationCodeServiceRegistry.class),
                mock(VerificationProviderResolver.class),
                mock(HumanVerificationServiceRegistry.class),
                passwordEncoder,
                () -> 42L,
                new PublicIdCodec(),
                new SpringRegistrationAfterCommitExecutor(cleanupObserver),
                mock(com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(600));
    }

    private static RegistrationFlowStore readyFlowStore() {
        RegistrationFlowStore flowStore = mock(RegistrationFlowStore.class);
        when(flowStore.claimCompletion(any(), any(), any())).thenAnswer(invocation ->
                new RegistrationCompletionClaim(
                        new RegistrationFlowSnapshot(
                                "postgres-integration@example.test",
                                "+13125550100",
                                true,
                                true,
                                true,
                                true,
                                NOW.minusSeconds(60),
                                NOW.plusSeconds(540)),
                        invocation.getArgument(1)));
        return flowStore;
    }

    private static RegistrationCompleteCommand command() {
        return new RegistrationCompleteCommand(
                new RegistrationAccess(
                        "register-token",
                        "flow-csrf",
                        "challenge-handle",
                        "550e8400-e29b-41d4-a716-446655440000",
                        "203.0.113.7"),
                "test-password",
                "test-password");
    }

    private static void applyMigrations() throws IOException, SQLException {
        List<String> migrations = List.of(
                "sql/001_create_users.sql",
                "sql/002_create_user_profile.sql",
                "sql/003_create_ai_model.sql",
                "sql/004_create_ai_model_capability.sql",
                "sql/005_create_user_membership_quota.sql",
                "sql/006_create_ai_model_icon.sql");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String migration : migrations) {
                statement.execute(Files.readString(
                        PROJECT_ROOT.resolve(migration), StandardCharsets.UTF_8));
            }
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

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionProxyConfiguration {}

    private static final class JdbcIdentityMapper implements UserLoginIdentityMapper {

        private final JdbcTemplate jdbc;

        private JdbcIdentityMapper(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public List<UserLoginIdentity> findConflicts(String email, String phone) {
            return jdbc.query(
                    """
                    SELECT id, email, phone
                    FROM userloginidentity
                    WHERE LOWER(email) = LOWER(?) OR phone = ?
                    LIMIT 2
                    """,
                    (resultSet, rowNumber) -> {
                        UserLoginIdentity identity = new UserLoginIdentity();
                        identity.setId(resultSet.getLong("id"));
                        identity.setEmail(resultSet.getString("email"));
                        identity.setPhone(resultSet.getString("phone"));
                        return identity;
                    },
                    email,
                    phone);
        }

        @Override
        public boolean existsById(long identityId) {
            Boolean exists = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM userloginidentity WHERE id = ?)",
                    Boolean.class,
                    identityId);
            return Boolean.TRUE.equals(exists);
        }

        @Override
        public int insert(UserLoginIdentity identity) {
            return jdbc.update(
                    """
                    INSERT INTO userloginidentity (
                        id, email, phone, password_hash
                    ) VALUES (?, ?, ?, ?)
                    """,
                    identity.getId(),
                    identity.getEmail(),
                    identity.getPhone(),
                    identity.getPasswordHash());
        }

        @Override
        public UserLoginIdentity findByNormalizedEmail(String normalizedEmail) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserLoginIdentity findByNormalizedPhone(String normalizedPhone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserLoginIdentity findByGithubSubject(String githubSubject) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserLoginIdentity findByGoogleSubject(String googleSubject) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserLoginIdentity findByNormalizedEmailForUpdate(String normalizedEmail) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserLoginIdentity findByIdForUpdate(long identityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UserLoginIdentity> findIdentityContactsAfterId(long afterId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int insertOAuthIdentityIfAbsent(UserLoginIdentity identity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int bindGithubSubjectIfAbsent(long identityId, String githubSubject) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int bindGoogleSubjectIfAbsent(long identityId, String googleSubject) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markEmailVerified(long identityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int fillPhoneIfAbsent(long identityId, String phone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.example.temperate.model.auth.domain.AuthenticationContext
                findAuthenticationByNormalizedEmail(String normalizedEmail) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.example.temperate.model.auth.domain.AuthenticationContext
                findAuthenticationByNormalizedPhone(String normalizedPhone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.example.temperate.model.auth.domain.AuthenticationContext
                findAuthenticationById(long identityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.example.temperate.model.auth.domain.AuthenticationContext>
                findAuthenticationByIds(List<Long> identityIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UserLoginIdentity> findByIds(List<Long> identityIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int batchInsertBoundaryFixtures(List<UserLoginIdentity> identities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.example.temperate.model.user.domain.CurrentUserProfile
                findCurrentUserProfileById(long identityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updatePasswordHash(Long id, String passwordHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updatePasswordHashAndIncrementVersion(
                long identityId,
                String passwordHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int upgradePasswordHashCas(
                long identityId, String expectedPasswordHash, String upgradedPasswordHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.example.temperate.model.auth.domain.TotpCredential findTotpCredentialById(
                long identityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int enableOrRotateTotp(
                long identityId,
                String encryptedSecret,
                boolean expectedEnabled,
                String expectedEncryptedSecret) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int disableTotp(long identityId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class JdbcProfileMapper implements UserProfileMapper {

        private final JdbcTemplate jdbc;
        private final AtomicBoolean identityDefaultsVisibleBeforeFailure;

        private JdbcProfileMapper(
                JdbcTemplate jdbc, AtomicBoolean identityDefaultsVisibleBeforeFailure) {
            this.jdbc = jdbc;
            this.identityDefaultsVisibleBeforeFailure = identityDefaultsVisibleBeforeFailure;
        }

        @Override
        public int insert(UserProfile profile) {
            Integer count = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM userloginidentity
                    WHERE id = ?
                      AND password_version = 1
                      AND created_at IS NOT NULL
                      AND updated_at IS NOT NULL
                    """,
                    Integer.class,
                    profile.getLoginIdentityId());
            identityDefaultsVisibleBeforeFailure.set(count != null && count == 1);
            return jdbc.update(
                    "INSERT INTO user_profile (login_identity_id, display_name) VALUES (?, ?)",
                    profile.getLoginIdentityId(),
                    profile.getDisplayName());
        }

        @Override
        public int batchInsertBoundaryFixtures(List<UserProfile> profiles) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UserProfile> findByLoginIdentityIds(List<Long> loginIdentityIds) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FixedTokenGenerator implements RegistrationTokenGenerator {

        @Override
        public String newRegisterToken() {
            return "register-token";
        }

        @Override
        public String newFlowCsrf() {
            return "flow-csrf";
        }

        @Override
        public String newChallengeHandle() {
            return "challenge-handle";
        }

        @Override
        public String newCompletionClaim() {
            return "completion-claim";
        }
    }
}
