package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.temperate.model.ai.entity.AiModel;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
 * 使用隔离 PostgreSQL 验证网关模型名称集合通过一次 Mapper 调用完成规范化批量匹配。
 */
@Testcontainers(disabledWithoutDocker = true)
final class AiModelDiscoveryMapperIntegrationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("ai_model_discovery_test")
            .withUsername("discovery_test")
            .withPassword("discovery_test_password");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void configure() throws Exception {
        applyMigrations();
        sqlSessionFactory = buildSqlSessionFactory();
        insertFixtures();
    }

    @Test
    void matchesOnlyRequestedNormalizedNamesInStableOrder() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            List<AiModel> models = session.getMapper(AiModelMapper.class)
                    .findByNormalizedModelNames(
                            List.of("gpt-5.4", "gpt-5.4-codex"));

            assertThat(models)
                    .extracting(AiModel::getModelName)
                    .containsExactly("gpt-5.4", "gpt-5.4-codex");
            assertThat(models)
                    .extracting(AiModel::getVendor)
                    .containsOnly("openai");
            assertThat(models)
                    .extracting(AiModel::getCachedInputRatio)
                    .allSatisfy(ratio ->
                            assertThat(ratio).isEqualByComparingTo("0.50000000"));
            assertThat(models)
                    .extracting(AiModel::getEnabled)
                    .containsExactly(true, false);
        }
    }

    private static void insertFixtures() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AiModelMapper mapper = session.getMapper(AiModelMapper.class);
            assertThat(mapper.insert(model(
                    801L, " GPT-5.4 ", "openai", "1", "4", true))).isEqualTo(1);
            assertThat(mapper.insert(model(
                    802L, "gpt-5.4-codex", "openai", "2", "8", false))).isEqualTo(1);
            assertThat(mapper.insert(model(
                    803L, "claude-sonnet", "anthropic", "3", "5", true))).isEqualTo(1);
        }
    }

    private static AiModel model(
            long id,
            String name,
            String vendor,
            String inputRatio,
            String outputRatio,
            boolean enabled) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setModelName(name);
        model.setDescription("discovery integration fixture");
        model.setTagsJson("[]");
        model.setModelNameTokensJson("[]");
        model.setDescriptionTokensJson("[]");
        model.setVendor(vendor);
        model.setInputRatio(new BigDecimal(inputRatio));
        model.setCachedInputRatio(new BigDecimal("0.50000000"));
        model.setOutputRatio(new BigDecimal(outputRatio));
        model.setEnabled(enabled);
        return model;
    }

    private static void applyMigrations() throws IOException, SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(read("sql/003_create_ai_model.sql"));
            statement.execute(read("sql/006_create_ai_model_icon.sql"));
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory() throws IOException {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                POSTGRES.getDriverClassName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Environment environment = new Environment(
                "discovery-test",
                new JdbcTransactionFactory(),
                dataSource);
        Configuration configuration = new Configuration(environment);
        try (InputStream inputStream =
                AiModelDiscoveryMapperIntegrationTest.class.getClassLoader()
                        .getResourceAsStream("mapper/ai/AiModelMapper.xml")) {
            assertNotNull(inputStream, "Missing AI model mapper XML");
            new XMLMapperBuilder(
                    inputStream,
                    configuration,
                    "mapper/ai/AiModelMapper.xml",
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
