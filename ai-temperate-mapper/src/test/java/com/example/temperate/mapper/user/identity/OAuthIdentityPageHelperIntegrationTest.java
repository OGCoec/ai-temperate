package com.example.temperate.mapper.user.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.auth.enums.RegistrationSource;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
 * 使用与运行实例一致的 PageHelper 插件链验证 OAuth 已有账号查询不会被分页状态或映射代理破坏。
 */
@Testcontainers(disabledWithoutDocker = true)
class OAuthIdentityPageHelperIntegrationTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15")
            .withDatabaseName("oauth_identity_pagehelper_test")
            .withUsername("oauth_mapper_test")
            .withPassword("oauth_mapper_test_password");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void configureDatabaseAndMyBatis() throws Exception {
        applySchema();
        insertExistingAccount();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @Test
    void existingPasswordAccountResolvesThroughRuntimePluginChain() {
        assertThat(PageHelper.getLocalPage()).isNull();

        try (SqlSession session = sqlSessionFactory.openSession()) {
            UserLoginIdentityMapper mapper = session.getMapper(UserLoginIdentityMapper.class);

            assertThat(mapper.findByGithubSubject("220595753")).isNull();
            UserLoginIdentity identity = mapper.findByNormalizedEmail("member@example.com");
            AuthenticationContext context = mapper.findAuthenticationById(41L);

            assertThat(identity).isNotNull();
            assertThat(identity.getId()).isEqualTo(41L);
            assertThat(identity.getRegistrationSource()).isEqualTo(RegistrationSource.STANDARD);
            assertThat(identity.getGithubSubject()).isNull();
            assertThat(identity.getGoogleSubject()).isNull();
            assertThat(identity.getEmail()).isEqualTo("member@example.com");
            assertThat(identity.getEmailVerified()).isFalse();
            assertThat(identity.getPhone()).isEqualTo("+12025550141");
            assertThat(identity.getPasswordHash()).isEqualTo("{bcrypt}oauth-existing-account");
            assertThat(context).isNotNull();
            assertThat(context.getIdentityId()).isEqualTo(41L);
            assertThat(context.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(context.getPhone()).isEqualTo("+12025550141");
        }

        assertThat(PageHelper.getLocalPage()).isNull();
    }

    private static void applySchema() throws IOException, SQLException {
        List<String> files = List.of(
                "sql/001_create_users.sql",
                "sql/002_create_user_profile.sql");
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement()) {
            for (String file : files) {
                statement.execute(Files.readString(
                        PROJECT_ROOT.resolve(file), StandardCharsets.UTF_8));
            }
        }
    }

    private static void insertExistingAccount() throws SQLException {
        String identitySql = """
                INSERT INTO userloginidentity (
                    id, registration_source, github_subject, google_subject,
                    email, email_verified, phone, password_hash,
                    totp_enabled, totp_secret_encrypted
                )
                VALUES (41, 0, NULL, NULL, ?, FALSE, ?, ?, TRUE, ?)
                """;
        String profileSql = """
                INSERT INTO user_profile (login_identity_id, display_name, account_status)
                VALUES (41, 'OAuth Existing Account', 0)
                """;
        try (Connection connection = openConnection();
                PreparedStatement identity = connection.prepareStatement(identitySql);
                Statement profile = connection.createStatement()) {
            identity.setString(1, "member@example.com");
            identity.setString(2, "+12025550141");
            identity.setString(3, "{bcrypt}oauth-existing-account");
            identity.setString(4, "v1.test-encrypted-totp");
            identity.executeUpdate();
            profile.executeUpdate(profileSql);
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory() throws IOException {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                POSTGRES.getDriverClassName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Environment environment = new Environment(
                "oauth-pagehelper-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        PageInterceptor pageInterceptor = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "postgresql");
        properties.setProperty("reasonable", "false");
        pageInterceptor.setProperties(properties);
        configuration.addInterceptor(pageInterceptor);
        parseMapper(configuration, "mapper/user/identity/UserLoginIdentityMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void parseMapper(Configuration configuration, String resource)
            throws IOException {
        try (InputStream inputStream = OAuthIdentityPageHelperIntegrationTest.class
                .getClassLoader()
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
