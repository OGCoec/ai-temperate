package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 在隔离 PostgreSQL 中验证 Generation 迁移、逻辑关联策略和跨实例活动任务唯一约束。
 */
@Testcontainers(disabledWithoutDocker = true)
class AiConversationGenerationMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("ai_generation_test")
            .withUsername("test")
            .withPassword("test");

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            executeSqlFile(statement, "sql/011_create_ai_conversation_generation.sql");
            executeSqlFile(statement, "sql/012_create_ai_conversation_generation_payload.sql");
        }
    }

    private static void executeSqlFile(Statement statement, String relativePath)
            throws Exception {
        String migration = Files.readString(
                findProjectRoot().resolve(relativePath), StandardCharsets.UTF_8);
        for (String sql : migration.split(";")) {
            if (!sql.isBlank()) {
                statement.execute(sql);
            }
        }
    }

    @Test
    void createsBothTablesWithoutPhysicalForeignKeys() throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name IN (
                              'ai_conversation_generation',
                              'ai_conversation_generation_payload')
                          AND constraint_type = 'FOREIGN KEY'
                        """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    @Test
    void createsRequiredIndexesAndChineseSchemaComments() throws Exception {
        Set<String> requiredIndexes = Set.of(
                "idx_ai_conversation_generation_recovery",
                "idx_ai_conversation_generation_owner",
                "idx_ai_conversation_generation_user_active",
                "idx_ai_conversation_generation_detached_due",
                "idx_ai_conversation_generation_conversation",
                "uq_ai_conversation_generation_conversation_active",
                "idx_ai_conversation_generation_model",
                "idx_ai_conversation_generation_payload_message");
        try (Connection connection = connection();
                PreparedStatement indexes = connection.prepareStatement("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND tablename IN (
                              'ai_conversation_generation',
                              'ai_conversation_generation_payload')
                        """);
                PreparedStatement comments = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM pg_class table_definition
                        JOIN pg_namespace namespace_definition
                          ON namespace_definition.oid = table_definition.relnamespace
                        JOIN pg_attribute attribute_definition
                          ON attribute_definition.attrelid = table_definition.oid
                        WHERE namespace_definition.nspname = 'public'
                          AND table_definition.relname IN (
                              'ai_conversation_generation',
                              'ai_conversation_generation_payload')
                          AND table_definition.relkind = 'r'
                          AND attribute_definition.attnum > 0
                          AND NOT attribute_definition.attisdropped
                          AND col_description(
                              table_definition.oid,
                              attribute_definition.attnum) IS NULL
                        """)) {
            java.util.HashSet<String> actualIndexes = new java.util.HashSet<>();
            try (ResultSet result = indexes.executeQuery()) {
                while (result.next()) {
                    actualIndexes.add(result.getString(1));
                }
            }
            assertThat(actualIndexes).containsAll(requiredIndexes);
            try (ResultSet result = comments.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    @Test
    void preventsTwoActiveGenerationsForOneConversationAndReleasesAfterTerminal()
            throws Exception {
        byte[] conversationId = bytes(16, 20);
        try (Connection connection = connection()) {
            insertGeneration(connection, 1, 11, 31, conversationId, 0);

            assertThatThrownBy(() ->
                    insertGeneration(connection, 2, 12, 32, conversationId, 0))
                    .isInstanceOf(SQLException.class);

            try (PreparedStatement terminal = connection.prepareStatement("""
                    UPDATE ai_conversation_generation
                    SET generation_status = 4
                    WHERE id = ?
                    """)) {
                terminal.setBytes(1, bytes(16, 1));
                assertThat(terminal.executeUpdate()).isEqualTo(1);
            }
            insertGeneration(connection, 2, 12, 32, conversationId, 0);
        }
    }

    @Test
    void skipLockedAllowsAnotherRecoveryConsumerToClaimTheNextRow() throws Exception {
        try (Connection seed = connection()) {
            insertGeneration(seed, 3, 13, 33, bytes(16, 21), 3);
            insertGeneration(seed, 4, 14, 34, bytes(16, 22), 3);
        }
        try (Connection first = connection(); Connection second = connection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            byte[] firstId = lockOne(first);
            byte[] secondId = lockOne(second);

            assertThat(firstId).isNotEqualTo(secondId);
            first.rollback();
            second.rollback();
        }
    }

    @Test
    void terminalVersionCasAllowsOnlyOneCompetingFact() throws Exception {
        byte[] generationId = bytes(16, 5);
        try (Connection seed = connection()) {
            insertGeneration(seed, 5, 15, 35, bytes(16, 23), 1);
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch contenderReady = new CountDownLatch(1);
        try (Connection winner = connection()) {
            winner.setAutoCommit(false);
            assertThat(freezeTerminal(winner, generationId, "COMPLETED"))
                    .isEqualTo(1);
            Future<Integer> contender = executor.submit(() -> {
                try (Connection connection = connection()) {
                    contenderReady.countDown();
                    return freezeTerminal(connection, generationId, "UPSTREAM_FAILED");
                }
            });
            contenderReady.await();
            winner.commit();

            assertThat(contender.get()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private static int freezeTerminal(
            Connection connection,
            byte[] generationId,
            String terminalType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_conversation_generation
                SET generation_status = 3,
                    terminal_type = ?,
                    terminal_reason = ?,
                    terminal_version = terminal_version + 1,
                    terminal_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND generation_status IN (0, 1, 2)
                  AND terminal_type IS NULL
                  AND terminal_version = 0
                """)) {
            statement.setString(1, terminalType);
            statement.setString(2, terminalType);
            statement.setBytes(3, generationId);
            return statement.executeUpdate();
        }
    }

    private static byte[] lockOne(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id
                FROM ai_conversation_generation
                WHERE generation_status = 3
                ORDER BY id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBytes(1);
            }
        }
    }

    private static void insertGeneration(
            Connection connection,
            int idMarker,
            int usageMarker,
            int digestMarker,
            byte[] conversationId,
            int status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_conversation_generation (
                    id, login_identity_id, conversation_id, usage_id,
                    idempotency_key_digest, model_id, generation_status,
                    observer_status, detached_at)
                VALUES (?, 42, ?, ?, ?, 7, ?, 1, CURRENT_TIMESTAMP)
                """)) {
            statement.setBytes(1, bytes(16, idMarker));
            statement.setBytes(2, conversationId);
            statement.setBytes(3, bytes(16, usageMarker));
            statement.setBytes(4, bytes(32, digestMarker));
            statement.setInt(5, status);
            statement.executeUpdate();
        }
    }

    private static byte[] bytes(int length, int marker) {
        byte[] value = new byte[length];
        value[length - 1] = (byte) marker;
        return value;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
