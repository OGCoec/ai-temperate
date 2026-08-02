package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.model.ai.entity.AiModelCapability;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 使用隔离 PostgreSQL 验证 AI 模型乐观锁、能力整组替换和事务回滚边界。
 */
@Testcontainers(disabledWithoutDocker = true)
final class AdminAiModelPatchIntegrationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final long MODEL_ID = 701L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("ai_model_patch_test")
            .withUsername("patch_test")
            .withPassword("patch_test_password");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void configure() throws Exception {
        applyMigrations();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @BeforeEach
    void resetFixture() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE ai_model_capability, ai_model, ai_model_icon");
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AiModelMapper modelMapper = session.getMapper(AiModelMapper.class);
            AiModelCapabilityMapper capabilityMapper =
                    session.getMapper(AiModelCapabilityMapper.class);
            assertThat(modelMapper.insert(model("gpt-5.5"))).isEqualTo(1);
            assertThat(capabilityMapper.insertBatch(java.util.List.of(
                    capability(AiModelCapabilityCode.RESPONSES)))).isEqualTo(1);
        }
    }

    @Test
    void staleWriterCannotOverwriteWinningVersion() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AiModelMapper mapper = session.getMapper(AiModelMapper.class);
            AiModel first = model("gpt-5.6");
            AiModel stale = model("gpt-stale");

            assertThat(mapper.updateEditable(first, 1L)).isEqualTo(1);
            assertThat(mapper.updateEditable(stale, 1L)).isZero();
            AiModel stored = mapper.findById(MODEL_ID);
            assertThat(stored.getModelName()).isEqualTo("gpt-5.6");
            assertThat(stored.getRowVersion()).isEqualTo(2L);
        }
    }

    @Test
    void editableUpdatePersistsCachedInputRatio() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AiModelMapper mapper = session.getMapper(AiModelMapper.class);
            AiModel changed = model("gpt-5.6");
            changed.setCachedInputRatio(new BigDecimal("0.25000000"));

            assertThat(mapper.updateEditable(changed, 1L)).isEqualTo(1);
            assertThat(mapper.findById(MODEL_ID).getCachedInputRatio())
                    .isEqualByComparingTo("0.25000000");
        }
    }

    @Test
    void insertQueryAndEditableUpdatePersistRawTokenLimits() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AiModelMapper mapper = session.getMapper(AiModelMapper.class);
            AiModel inserted = mapper.findById(MODEL_ID);

            assertThat(inserted.getContextWindowTokens()).isEqualTo(256000L);
            assertThat(inserted.getMaxOutputTokens()).isEqualTo(32000L);
            assertThat(mapper.findByIds(java.util.List.of(MODEL_ID)).get(0)
                    .getContextWindowTokens()).isEqualTo(256000L);
            assertThat(mapper.findPage(null, null, null, null).get(0)
                    .getMaxOutputTokens()).isEqualTo(32000L);
            assertThat(mapper.findEnabled(10).get(0)
                    .getContextWindowTokens()).isEqualTo(256000L);

            AiModel changed = model("gpt-5.6");
            changed.setContextWindowTokens(512000L);
            changed.setMaxOutputTokens(64000L);
            assertThat(mapper.updateEditable(changed, 1L)).isEqualTo(1);

            AiModel stored = mapper.findById(MODEL_ID);
            assertThat(stored.getContextWindowTokens()).isEqualTo(512000L);
            assertThat(stored.getMaxOutputTokens()).isEqualTo(64000L);
        }
    }

    @Test
    void databaseEnforcesPairedPositiveBoundedKMultiplesAndOutputRelation()
            throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : java.util.List.of(
                    "UPDATE ai_model SET max_output_tokens = NULL WHERE id = " + MODEL_ID,
                    "UPDATE ai_model SET context_window_tokens = 0 WHERE id = " + MODEL_ID,
                    "UPDATE ai_model SET context_window_tokens = 2147483647001,"
                            + " max_output_tokens = 32000 WHERE id = " + MODEL_ID,
                    "UPDATE ai_model SET context_window_tokens = 256001 WHERE id = " + MODEL_ID,
                    "UPDATE ai_model SET context_window_tokens = 32000,"
                            + " max_output_tokens = 64000 WHERE id = " + MODEL_ID)) {
                assertThatThrownBy(() -> statement.execute(sql))
                        .isInstanceOf(SQLException.class);
            }

            assertThat(statement.executeUpdate(
                    "UPDATE ai_model SET context_window_tokens = NULL,"
                            + " max_output_tokens = NULL WHERE id = " + MODEL_ID))
                    .isEqualTo(1);
            assertThat(statement.executeUpdate(
                    "UPDATE ai_model SET context_window_tokens = 2147483647000,"
                            + " max_output_tokens = 2147483647000 WHERE id = " + MODEL_ID))
                    .isEqualTo(1);
        }
    }

    @Test
    void mainUpdateAndCapabilityReplacementRollBackTogether() {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            AiModelMapper modelMapper = session.getMapper(AiModelMapper.class);
            AiModelCapabilityMapper capabilityMapper =
                    session.getMapper(AiModelCapabilityMapper.class);
            AiModel changed = model("must-roll-back");

            assertThat(modelMapper.updateEditable(changed, 1L)).isEqualTo(1);
            assertThat(capabilityMapper.deleteByAiModelId(MODEL_ID)).isEqualTo(1);
            assertThat(capabilityMapper.insertBatch(java.util.List.of(
                    capability(AiModelCapabilityCode.IMAGE)))).isEqualTo(1);
            session.rollback();
        }

        try (SqlSession verification = sqlSessionFactory.openSession()) {
            AiModelMapper modelMapper = verification.getMapper(AiModelMapper.class);
            AiModelCapabilityMapper capabilityMapper =
                    verification.getMapper(AiModelCapabilityMapper.class);
            assertThat(modelMapper.findById(MODEL_ID).getModelName()).isEqualTo("gpt-5.5");
            assertThat(modelMapper.findById(MODEL_ID).getRowVersion()).isEqualTo(1L);
            assertThat(capabilityMapper.findByAiModelId(MODEL_ID))
                    .extracting(AiModelCapability::getCapabilityCode)
                    .containsExactly(AiModelCapabilityCode.RESPONSES);
        }
    }

    @Test
    void leftJoinReturnsNullWithoutIconAndResolvedUrlWithIcon() throws SQLException {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            AiModel withoutIcon = session.getMapper(AiModelMapper.class).findById(MODEL_ID);
            assertThat(withoutIcon.getIconId()).isNull();
            assertThat(withoutIcon.getIcon()).isNull();
        }

        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO ai_model_icon (
                        id,
                        icon_name,
                        icon_url,
                        description
                    )
                    OVERRIDING SYSTEM VALUE
                    VALUES (
                        901,
                        'OpenAI',
                        'https://cdn.example.test/openai.png',
                        'OpenAI model family'
                    )
                    """);
            statement.execute("UPDATE ai_model SET icon_id = 901 WHERE id = " + MODEL_ID);
        }

        try (SqlSession session = sqlSessionFactory.openSession()) {
            AiModel withIcon = session.getMapper(AiModelMapper.class).findById(MODEL_ID);
            assertThat(withIcon.getIconId()).isEqualTo(901L);
            assertThat(withIcon.getIcon())
                    .isEqualTo("https://cdn.example.test/openai.png");
        }
    }

    private static AiModel model(String name) {
        AiModel model = new AiModel();
        model.setId(MODEL_ID);
        model.setModelName(name);
        model.setDescription("integration model");
        model.setIconId(null);
        model.setTagsJson("[\"chat\"]");
        model.setModelNameTokensJson("[\"gpt\"]");
        model.setDescriptionTokensJson("[\"integration\",\"model\"]");
        model.setVendor("openai");
        model.setInputRatio(BigDecimal.ONE);
        model.setCachedInputRatio(new BigDecimal("0.50000000"));
        model.setOutputRatio(BigDecimal.TWO);
        model.setContextWindowTokens(256000L);
        model.setMaxOutputTokens(32000L);
        model.setEnabled(true);
        return model;
    }

    private static AiModelCapability capability(AiModelCapabilityCode code) {
        AiModelCapability capability = new AiModelCapability();
        capability.setId(1000L + code.ordinal());
        capability.setAiModelId(MODEL_ID);
        capability.setCapabilityCode(code);
        return capability;
    }

    private static void applyMigrations() throws IOException, SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(read("sql/003_create_ai_model.sql"));
            statement.execute(read("sql/004_create_ai_model_capability.sql"));
            statement.execute(read("sql/006_create_ai_model_icon.sql"));
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory() throws IOException {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                POSTGRES.getDriverClassName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Environment environment =
                new Environment("patch-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        parseMapper(configuration, "mapper/ai/AiModelMapper.xml");
        parseMapper(configuration, "mapper/ai/AiModelCapabilityMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void parseMapper(Configuration configuration, String resource)
            throws IOException {
        try (InputStream inputStream =
                AdminAiModelPatchIntegrationTest.class.getClassLoader()
                        .getResourceAsStream(resource)) {
            assertNotNull(inputStream, () -> "Missing mapper XML: " + resource);
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
