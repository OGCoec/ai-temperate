package com.example.temperate.mapper.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.temperate.model.ai.entity.AiModel;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageInterceptor;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
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
 * 使用隔离 PostgreSQL 验证 PageHelper 对 AI 模型页码、四种安全排序和复合索引扫描的真实集成行为。
 */
@Testcontainers(disabledWithoutDocker = true)
final class AdminAiModelPageHelperIntegrationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("ai_model_pagehelper_test")
            .withUsername("pagehelper_test")
            .withPassword("pagehelper_test_password");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void configureDatabaseAndMyBatis() throws IOException, SQLException {
        applyAiModelMigration();
        sqlSessionFactory = buildSqlSessionFactory();
        insertFixtures();
    }

    @Test
    void paginatesInputFirstInBothDirections() {
        PageInfo<AiModel> ascending = page(
                1,
                2,
                "input_ratio ASC, output_ratio ASC, model_name ASC");
        PageInfo<AiModel> descending = page(
                1,
                2,
                "input_ratio DESC, output_ratio DESC, model_name DESC");

        assertPage(ascending, List.of("beta", "alpha"));
        assertPage(descending, List.of("delta", "gamma"));
    }

    @Test
    void paginatesOutputFirstInBothDirections() {
        PageInfo<AiModel> ascending = page(
                1,
                2,
                "output_ratio ASC, input_ratio ASC, model_name ASC");
        PageInfo<AiModel> descending = page(
                1,
                2,
                "output_ratio DESC, input_ratio DESC, model_name DESC");

        assertPage(ascending, List.of("gamma", "delta"));
        assertPage(descending, List.of("alpha", "beta"));
    }

    @Test
    void keepsPaginationStateInsideOneMapperQuery() {
        PageInfo<AiModel> secondPage = page(
                2,
                2,
                "input_ratio ASC, output_ratio ASC, model_name ASC");

        assertThat(secondPage.getPageNum()).isEqualTo(2);
        assertThat(secondPage.getPageSize()).isEqualTo(2);
        assertThat(secondPage.getTotal()).isEqualTo(4);
        assertThat(secondPage.getPages()).isEqualTo(2);
        assertThat(secondPage.isHasPreviousPage()).isTrue();
        assertThat(secondPage.isHasNextPage()).isFalse();
        assertThat(secondPage.getList())
                .extracting(AiModel::getModelName)
                .containsExactly("gamma", "delta");
        assertThat(PageHelper.getLocalPage()).isNull();
    }

    @Test
    void filtersByCompleteGinTokensAndExactVendorBeforePagination() {
        PageInfo<AiModel> enabledMini = page(
                1,
                50,
                "input_ratio ASC, output_ratio ASC, model_name ASC",
                "[\"mini\"]",
                "[\"mini\"]",
                "mini",
                true);
        PageInfo<AiModel> allNameTokens = page(
                1,
                50,
                "input_ratio ASC, output_ratio ASC, model_name ASC",
                "[\"gpt\",\"mini\"]",
                null,
                "gpt-mini",
                true);
        PageInfo<AiModel> versionToken = page(
                1,
                50,
                "input_ratio ASC, output_ratio ASC, model_name ASC",
                "[\"5.4\"]",
                "[\"5.4\"]",
                "5.4",
                true);
        PageInfo<AiModel> partialNameToken = page(
                1,
                50,
                "input_ratio ASC, output_ratio ASC, model_name ASC",
                "[\"min\"]",
                "[\"min\"]",
                "min",
                true);
        PageInfo<AiModel> exactVendor = page(
                1,
                50,
                "input_ratio ASC, output_ratio ASC, model_name ASC",
                null,
                null,
                "OPENAI".toLowerCase(),
                true);
        PageInfo<AiModel> vendorPrefix = page(
                1,
                50,
                "input_ratio ASC, output_ratio ASC, model_name ASC",
                null,
                null,
                "open",
                true);

        assertThat(enabledMini.getList())
                .extracting(AiModel::getModelName)
                .containsExactly("gpt-5.4-mini");
        assertThat(allNameTokens.getList())
                .extracting(AiModel::getModelName)
                .containsExactly("gpt-5.4-mini");
        assertThat(versionToken.getList())
                .extracting(AiModel::getModelName)
                .containsExactly("gpt-5.4-mini");
        assertThat(partialNameToken.getList()).isEmpty();
        assertThat(exactVendor.getList())
                .extracting(AiModel::getModelName)
                .containsExactly("gpt-alpha", "gpt-5.4-mini");
        assertThat(vendorPrefix.getList()).isEmpty();
    }

    @Test
    void descriptionGinTokensReturnRowsIndependentlyFromModelName() {
        PageInfo<AiModel> result = page(
                1,
                50,
                "input_ratio ASC, output_ratio ASC, model_name ASC",
                null,
                "[\"reasoning\",\"fast\"]",
                "reasoning fast",
                true);

        assertThat(result.getList())
                .extracting(AiModel::getModelName)
                .containsExactly("alpha");
    }

    @Test
    void existingCompositeIndexesSupportForwardAndBackwardSorts()
            throws SQLException {
        assertIndexPlan(
                "input_ratio ASC, output_ratio ASC, model_name ASC",
                "idx_ai_model_input_output_name");
        assertIndexPlan(
                "input_ratio DESC, output_ratio DESC, model_name DESC",
                "idx_ai_model_input_output_name");
        assertIndexPlan(
                "output_ratio ASC, input_ratio ASC, model_name ASC",
                "idx_ai_model_output_input_name");
        assertIndexPlan(
                "output_ratio DESC, input_ratio DESC, model_name DESC",
                "idx_ai_model_output_input_name");
    }

    @Test
    void existingGinIndexesSupportBothTokenColumns() throws SQLException {
        assertTokenIndexPlan(
                "model_name_tokens",
                "[\"mini\"]",
                "idx_ai_model_model_name_tokens_gin");
        assertTokenIndexPlan(
                "description_tokens",
                "[\"reasoning\"]",
                "idx_ai_model_description_tokens_gin");
        assertExactVendorIndexPlan();
    }

    private static PageInfo<AiModel> page(
            int pageNum,
            int pageSize,
            String orderBy) {
        return page(pageNum, pageSize, orderBy, null, null, "fixture-vendor", null);
    }

    private static PageInfo<AiModel> page(
            int pageNum,
            int pageSize,
            String orderBy,
            String modelNameTokensJson,
            String descriptionTokensJson,
            String vendorExact,
            Boolean enabled) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            AiModelMapper mapper = session.getMapper(AiModelMapper.class);
            Page<AiModel> page = PageHelper.startPage(pageNum, pageSize, true);
            try {
                page.setOrderBy(orderBy);
                return PageInfo.of(mapper.findPage(
                        modelNameTokensJson,
                        descriptionTokensJson,
                        vendorExact,
                        enabled));
            } finally {
                PageHelper.clearPage();
            }
        }
    }

    private static void assertPage(PageInfo<AiModel> page, List<String> expectedNames) {
        assertThat(page.getPageNum()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(2);
        assertThat(page.getTotal()).isEqualTo(4);
        assertThat(page.getPages()).isEqualTo(2);
        assertThat(page.isHasPreviousPage()).isFalse();
        assertThat(page.isHasNextPage()).isTrue();
        assertThat(page.getList())
                .extracting(AiModel::getModelName)
                .containsExactlyElementsOf(expectedNames);
    }

    private static void assertIndexPlan(String orderBy, String expectedIndex)
            throws SQLException {
        List<String> plan = new ArrayList<>();
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            try (ResultSet result = statement.executeQuery(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT id, model_name, input_ratio, output_ratio "
                            + "FROM ai_model ORDER BY " + orderBy + " LIMIT 2")) {
                while (result.next()) {
                    plan.add(result.getString(1));
                }
            }
        }
        assertThat(String.join("\n", plan)).contains(expectedIndex);
    }

    private static void assertTokenIndexPlan(
            String column,
            String tokensJson,
            String expectedIndex) throws SQLException {
        List<String> plan = new ArrayList<>();
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            try (ResultSet result = statement.executeQuery(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM ai_model WHERE "
                            + column + " @> '" + tokensJson + "'::JSONB")) {
                while (result.next()) {
                    plan.add(result.getString(1));
                }
            }
        }
        assertThat(String.join("\n", plan)).contains(expectedIndex);
    }

    private static void assertExactVendorIndexPlan() throws SQLException {
        List<String> plan = new ArrayList<>();
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            try (ResultSet result = statement.executeQuery(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM ai_model "
                            + "WHERE LOWER(vendor) = 'openai'")) {
                while (result.next()) {
                    plan.add(result.getString(1));
                }
            }
        }
        assertThat(String.join("\n", plan)).contains("idx_ai_model_vendor_prefix_ci");
    }

    private static void insertFixtures() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AiModelMapper mapper = session.getMapper(AiModelMapper.class);
            assertThat(mapper.insert(model(
                    101L, "alpha", "fixture-vendor", "1", "3", true))).isEqualTo(1);
            assertThat(mapper.insert(model(
                    102L, "beta", "fixture-vendor", "1", "2", true))).isEqualTo(1);
            assertThat(mapper.insert(model(
                    103L, "gamma", "fixture-vendor", "2", "1", true))).isEqualTo(1);
            assertThat(mapper.insert(model(
                    104L, "delta", "fixture-vendor", "3", "1", true))).isEqualTo(1);
            assertThat(mapper.insert(model(
                    105L, "gpt-alpha", "openai", "4", "4", true))).isEqualTo(1);
            assertThat(mapper.insert(model(
                    106L, "gpt-disabled", "openai", "5", "5", false))).isEqualTo(1);
            assertThat(mapper.insert(model(
                    107L, "gpt%_literal", "literal-vendor", "6", "6", true))).isEqualTo(1);
            AiModel mini = model(
                    108L, "gpt-5.4-mini", "openai", "7", "7", true);
            mini.setModelNameTokensJson("[\"gpt\",\"5.4\",\"mini\"]");
            mini.setDescriptionTokensJson("[\"gpt\",\"mini\"]");
            assertThat(mapper.insert(mini)).isEqualTo(1);
        }
    }

    private static AiModel model(
            long id,
            String modelName,
            String vendor,
            String inputRatio,
            String outputRatio,
            boolean enabled) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setModelName(modelName);
        model.setDescription("PageHelper integration fixture");
        model.setTagsJson("[]");
        model.setModelNameTokensJson(modelNameTokensJson(modelName));
        model.setDescriptionTokensJson(
                "alpha".equals(modelName)
                        ? "[\"fast\",\"reasoning\"]"
                        : "[\"fixture\",\"integration\",\"pagehelper\"]");
        model.setVendor(vendor);
        model.setInputRatio(new BigDecimal(inputRatio));
        model.setCachedInputRatio(new BigDecimal("0.50000000"));
        model.setOutputRatio(new BigDecimal(outputRatio));
        model.setEnabled(enabled);
        return model;
    }

    private static String modelNameTokensJson(String modelName) {
        return switch (modelName) {
            case "gpt-alpha" -> "[\"gpt\",\"alpha\"]";
            case "gpt-disabled" -> "[\"gpt\",\"disabled\"]";
            default -> "[\"" + modelName + "\"]";
        };
    }

    private static void applyAiModelMigration() throws IOException, SQLException {
        String createSql = Files.readString(
                PROJECT_ROOT.resolve("sql/003_create_ai_model.sql"),
                StandardCharsets.UTF_8);
        String iconSql = Files.readString(
                PROJECT_ROOT.resolve("sql/006_create_ai_model_icon.sql"),
                StandardCharsets.UTF_8);
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(createSql);
            statement.execute(iconSql);
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory() throws IOException {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                POSTGRES.getDriverClassName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Environment environment =
                new Environment("pagehelper-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        PageInterceptor pageInterceptor = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "postgresql");
        properties.setProperty("reasonable", "false");
        pageInterceptor.setProperties(properties);
        configuration.addInterceptor(pageInterceptor);
        parseMapper(configuration, "mapper/ai/AiModelMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void parseMapper(Configuration configuration, String resource)
            throws IOException {
        try (InputStream inputStream =
                AdminAiModelPageHelperIntegrationTest.class.getClassLoader()
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

    private static Connection openConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
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
