package com.example.temperate.mapper.user.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 验证 OAuth 身份列、唯一索引和并发安全绑定 SQL 的静态持久化合同。
 */
class OAuthIdentityMapperContractTest {

    @Test
    void baseSchemaAllowsThreeLoginMethodsOnOneIdentityRow() throws Exception {
        String sql = Files.readString(
                Path.of("..", "sql", "001_create_users.sql"),
                StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertThat(sql).contains("registration_source smallint not null default 0");
        assertThat(sql).contains("github_subject varchar(255)");
        assertThat(sql).contains("google_subject varchar(255)");
        assertThat(sql).contains("email_verified boolean not null default false");
        assertThat(sql).contains("password_hash varchar(255)");
        assertThat(sql).doesNotContain("password_hash varchar(255) not null");
        assertThat(sql).contains("check (registration_source in (0, 1, 2))");
        assertThat(sql).contains("uk_userloginidentity_github_subject");
        assertThat(sql).contains("uk_userloginidentity_google_subject");
    }

    @Test
    void mapperPersistsAndBindsProviderSubjectsWithoutOverwriting() throws Exception {
        String xml = Files.readString(
                Path.of("src", "main", "resources", "mapper", "user", "identity",
                        "UserLoginIdentityMapper.xml"),
                StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertThat(xml).contains("property=\"registrationsource\" column=\"registration_source\"");
        assertThat(xml).contains(
                "typehandler=\"com.example.temperate.mapper.typehandler."
                        + "registrationsourcetypehandler\"");
        assertThat(xml).doesNotContain(
                "org.apache.ibatis.type.enumtypehandler",
                "case registration_source");
        assertThat(xml).contains("property=\"githubsubject\" column=\"github_subject\"");
        assertThat(xml).contains("property=\"googlesubject\" column=\"google_subject\"");
        assertThat(xml).contains("property=\"emailverified\" column=\"email_verified\"");
        assertThat(xml).contains("github_subject is null");
        assertThat(xml).contains("google_subject is null");
        assertThat(xml).contains("phone is null");
        assertThat(xml).contains("id=\"insertoauthidentityifabsent\"");
        assertThat(xml).contains("on conflict do nothing");
    }

    @Test
    void incrementalMigrationRestoresSubjectIndexesWithoutTransactionWrapper()
            throws Exception {
        Path migration = Path.of(
                "..", "sql", "migrations",
                "026_restore_userloginidentity_oauth_subject_indexes.sql");
        assertThat(migration).exists();

        String sql = Files.readString(migration, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        String normalized = sql.replaceAll("\\s+", " ");

        assertThat(normalized).contains(
                "create unique index concurrently if not exists "
                        + "uk_userloginidentity_github_subject",
                "create unique index concurrently if not exists "
                        + "uk_userloginidentity_google_subject",
                "where github_subject is not null",
                "where google_subject is not null");
        assertThat(normalized).doesNotContain("begin;", "commit;");
    }

    @Test
    void preflightCheckReturnsOnlyCountsAndIndexValidity() throws Exception {
        Path check = Path.of(
                "..", "sql", "checks",
                "userloginidentity_oauth_subject_uniqueness.sql");
        assertThat(check).exists();

        String sql = Files.readString(check, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);

        assertThat(sql).contains(
                "github_duplicate_group_count",
                "google_duplicate_group_count",
                "github_index_valid",
                "google_index_valid");
        assertThat(sql).doesNotContain("select github_subject,", "select google_subject,");
    }
}
