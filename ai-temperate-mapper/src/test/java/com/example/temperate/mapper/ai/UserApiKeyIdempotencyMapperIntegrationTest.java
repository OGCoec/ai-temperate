package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.temperate.model.ai.entity.UserApiKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * 该集成测试是来验证 API Key 创建 UUID 的映射、部分唯一索引和 ON CONFLICT 路径不会使 PostgreSQL 事务失败。
 */
@Testcontainers(disabledWithoutDocker = true)
final class UserApiKeyIdempotencyMapperIntegrationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("api_key_idempotency_test")
            .withUsername("api_key_test")
            .withPassword("api_key_test_password");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void configure() throws Exception {
        applyMigration();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @Test
    void duplicateUuidReturnsZeroAndTheSameTransactionCanStillReadTheWinner() {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            UserApiKeyMapper mapper = session.getMapper(UserApiKeyMapper.class);
            UserApiKey winner = key(IDEMPOTENCY_KEY, (byte) 1, "Ab3D");
            UserApiKey duplicate = key(IDEMPOTENCY_KEY, (byte) 2, "Ef4G");
            UserApiKey distinctIntent = key(
                    UUID.fromString("4b6a6142-6b43-44d8-a53d-df2fe483b95e"),
                    (byte) 4,
                    "Kl6M");

            assertThat(mapper.insert(winner)).isEqualTo(1);
            assertThat(winner.getId()).isPositive();
            assertThat(mapper.insert(duplicate)).isZero();
            assertThat(mapper.insert(distinctIntent)).isEqualTo(1);

            UserApiKey persisted = mapper.findByCreateIdempotencyKey(IDEMPOTENCY_KEY);
            assertThat(persisted.getId()).isEqualTo(winner.getId());
            assertThat(persisted.getCreateIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
            assertThat(persisted.getKeyDigest()).containsExactly(winner.getKeyDigest());
            assertThat(distinctIntent.getId()).isPositive().isNotEqualTo(winner.getId());
            session.commit();
        }
    }

    @Test
    void historicalNullIdempotencyKeyRemainsReadable() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserApiKeyMapper mapper = session.getMapper(UserApiKeyMapper.class);
            UserApiKey historical = key(null, (byte) 3, "Hi5J");

            assertThat(mapper.insert(historical)).isEqualTo(1);

            UserApiKey persisted = mapper.findOwnedById(historical.getId(), 17L);
            assertThat(persisted).isNotNull();
            assertThat(persisted.getCreateIdempotencyKey()).isNull();
        }
    }

    @Test
    void concurrentSameUuidStillCreatesExactlyOneRow() throws Exception {
        UUID concurrentKey = UUID.fromString("c9679d40-5e6a-49cf-b34a-8d537e66504f");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks = List.of(
                    () -> concurrentInsert(concurrentKey, (byte) 5, "No7P", ready, start),
                    () -> concurrentInsert(concurrentKey, (byte) 6, "Qr8S", ready, start));
            var futures = List.of(
                    executor.submit(tasks.get(0)),
                    executor.submit(tasks.get(1)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> results = List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results)
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static int concurrentInsert(
            UUID idempotencyKey,
            byte digestByte,
            String hint,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent API Key insert did not start");
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.getMapper(UserApiKeyMapper.class)
                    .insert(key(idempotencyKey, digestByte, hint));
        }
    }

    private static UserApiKey key(UUID idempotencyKey, byte digestByte, String hint) {
        UserApiKey key = new UserApiKey();
        key.setLoginIdentityId(17L);
        key.setCreateIdempotencyKey(idempotencyKey);
        byte[] digest = new byte[32];
        Arrays.fill(digest, digestByte);
        key.setKeyDigest(digest);
        key.setKeyHint(hint);
        key.setStatus(1);
        return key;
    }

    private static void applyMigration() throws IOException, SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(read("sql/014_create_user_api_key.sql"));
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory() throws IOException {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                POSTGRES.getDriverClassName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Environment environment = new Environment(
                "api-key-idempotency-test",
                new JdbcTransactionFactory(),
                dataSource);
        Configuration configuration = new Configuration(environment);
        try (InputStream inputStream =
                UserApiKeyIdempotencyMapperIntegrationTest.class.getClassLoader()
                        .getResourceAsStream("mapper/ai/UserApiKeyMapper.xml")) {
            assertNotNull(inputStream, "Missing API Key mapper XML");
            new XMLMapperBuilder(
                    inputStream,
                    configuration,
                    "mapper/ai/UserApiKeyMapper.xml",
                    configuration.getSqlFragments())
                    .parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static Connection openConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(
                PROJECT_ROOT.resolve(relativePath),
                StandardCharsets.UTF_8);
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
