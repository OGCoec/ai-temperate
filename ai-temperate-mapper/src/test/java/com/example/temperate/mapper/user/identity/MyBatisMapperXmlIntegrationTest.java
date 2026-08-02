package com.example.temperate.mapper.user.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * 验证 MyBatis Mapper 接口与 XML 语句可在 Spring 持久化上下文中正确绑定。
 */
class MyBatisMapperXmlIntegrationTest {

    @Test
    void identityMapperXmlBuildsAllMappedStatements() throws IOException {
        Configuration configuration = parseMapper(
                "mapper/user/identity/UserLoginIdentityMapper.xml");

        String namespace = "com.example.temperate.mapper.user.identity.UserLoginIdentityMapper.";
        assertTrue(configuration.hasStatement(namespace + "findConflicts"));
        assertTrue(configuration.hasStatement(namespace + "findByNormalizedEmail"));
        assertTrue(configuration.hasStatement(namespace + "findByNormalizedPhone"));
        assertTrue(configuration.hasStatement(namespace + "insert"));
        assertTrue(configuration.hasStatement(namespace + "updatePasswordHash"));

        String conflictSql = configuration.getMappedStatement(namespace + "findConflicts")
                .getBoundSql(Map.of(
                        "normalizedEmail", "person@example.com",
                        "normalizedPhone", "+15551234567"))
                .getSql()
                .toLowerCase(Locale.ROOT);
        assertFalse(conflictSql.contains("password_hash"));
    }

    @Test
    void profileMapperXmlBuildsInsertStatement() throws IOException {
        Configuration configuration = parseMapper("mapper/user/profile/UserProfileMapper.xml");

        String statementId = "com.example.temperate.mapper.user.profile.UserProfileMapper.insert";
        assertTrue(configuration.hasStatement(statementId));

        var boundSql = configuration.getMappedStatement(statementId)
                .getBoundSql(Map.of(
                        "loginIdentityId", 10001L,
                        "displayName", "Temperate User"));
        String insertSql = boundSql.getSql().toLowerCase(Locale.ROOT);

        assertTrue(insertSql.contains("display_name"));
        assertTrue(boundSql.getParameterMappings().stream()
                .anyMatch(mapping -> "displayName".equals(mapping.getProperty())));
        assertFalse(insertSql.contains("membership_tier"));
    }

    @Test
    void membershipQuotaMapperXmlBuildsInsertAndLookupStatements() throws IOException {
        Configuration configuration = parseMapper(
                "mapper/user/membership/UserMembershipQuotaMapper.xml");
        String namespace =
                "com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper.";

        assertTrue(configuration.hasStatement(namespace + "insert"));
        assertTrue(configuration.hasStatement(namespace + "findByLoginIdentityId"));

        var insert = configuration.getMappedStatement(namespace + "insert")
                .getBoundSql(Map.of(
                        "loginIdentityId", 10001L,
                        "membershipTier", 0,
                        "quotaBalanceMinor", 5_000L));
        String insertSql = insert.getSql().toLowerCase(Locale.ROOT);
        assertTrue(insertSql.contains("insert into user_membership_quota"));
        assertTrue(insertSql.contains("membership_tier"));
        assertTrue(insertSql.contains("quota_balance_minor"));
        assertTrue(insertSql.contains("quota_period_started_at"));
        assertTrue(insertSql.contains("quota_period_ends_at"));
        assertTrue(insert.getParameterMappings().stream()
                .anyMatch(mapping -> "loginIdentityId".equals(mapping.getProperty())));
        assertTrue(insert.getParameterMappings().stream()
                .anyMatch(mapping -> "membershipTier".equals(mapping.getProperty())));
        assertTrue(insert.getParameterMappings().stream()
                .anyMatch(mapping -> "quotaBalanceMinor".equals(mapping.getProperty())));
        assertTrue(insert.getParameterMappings().stream()
                .anyMatch(mapping -> "quotaPeriodStartedAt".equals(mapping.getProperty())));
        assertTrue(insert.getParameterMappings().stream()
                .anyMatch(mapping -> "quotaPeriodEndsAt".equals(mapping.getProperty())));

        var lookup = configuration.getMappedStatement(namespace + "findByLoginIdentityId")
                .getBoundSql(Map.of("loginIdentityId", 10001L));
        String lookupSql = lookup.getSql().toLowerCase(Locale.ROOT);
        assertTrue(lookupSql.contains("quota_period_started_at"));
        assertTrue(lookupSql.contains("quota_period_ends_at"));
    }

    private static Configuration parseMapper(String resource) throws IOException {
        Path mapperPath = Path.of("src/main/resources").resolve(resource);
        assertTrue(Files.isRegularFile(mapperPath), () -> "Missing mapper XML: " + mapperPath);

        Configuration configuration = new Configuration();
        try (InputStream inputStream = Files.newInputStream(mapperPath)) {
            XMLMapperBuilder builder = new XMLMapperBuilder(
                    inputStream,
                    configuration,
                    resource,
                    configuration.getSqlFragments());
            builder.parse();
        }
        return configuration;
    }
}
