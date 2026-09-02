package com.example.temperate.mapper.user.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 用于验证用户资料相关 SQL 映射符合约定的表结构、参数绑定和批量写入边界。
 */
class PersistenceSqlContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void baseIdentitySchemaDefinesPasswordVersionWithoutLegacyPolicyState()
            throws IOException {
        String baseIdentitySchema =
                normalizedSql(readRequired(PROJECT_ROOT.resolve("sql/001_create_users.sql")));

        assertTrue(baseIdentitySchema.contains(
                "password_version bigint not null default 1"));
        assertTrue(baseIdentitySchema.contains(
                "constraint chk_userloginidentity_password_version_positive"));
        assertTrue(baseIdentitySchema.contains("check (password_version > 0)"));
        assertTrue(baseIdentitySchema.contains(
                "totp_enabled boolean not null default false"));
        assertTrue(baseIdentitySchema.contains(
                "totp_secret_encrypted varchar(512)"));
        assertTrue(baseIdentitySchema.contains(
                "comment on column userloginidentity.totp_enabled is"));
        assertTrue(baseIdentitySchema.contains(
                "comment on column userloginidentity.totp_secret_encrypted is"));
        assertFalse(baseIdentitySchema.contains("password_strength_level"));
        assertFalse(baseIdentitySchema.contains("password_policy_version"));
        assertFalse(baseIdentitySchema.contains("email_verified_at"));
        assertFalse(baseIdentitySchema.contains("phone_verified_at"));
        assertFalse(baseIdentitySchema.contains("password_changed_at"));
        assertFalse(baseIdentitySchema.contains("alter table userloginidentity"));
        assertTrue(baseIdentitySchema.contains(
                "create unique index uk_userloginidentity_email_lower "
                        + "on userloginidentity (lower(email))"));
        assertTrue(baseIdentitySchema.contains(
                "create unique index uk_userloginidentity_phone "
                        + "on userloginidentity (phone) where phone is not null"));
        assertFalse(Files.exists(PROJECT_ROOT.resolve(
                "sql/004_add_userloginidentity_verification_timestamps.sql")));
        assertFalse(Files.exists(PROJECT_ROOT.resolve(
                "sql/005_add_userloginidentity_password_version.sql")));
        assertFalse(Files.exists(PROJECT_ROOT.resolve(
                "sql/rollback/005_remove_userloginidentity_password_version.sql")));
        assertFalse(Files.exists(PROJECT_ROOT.resolve(
                "sql/006_add_userloginidentity_password_policy.sql")));
        assertFalse(Files.exists(PROJECT_ROOT.resolve(
                "sql/rollback/006_remove_userloginidentity_password_policy.sql")));
        assertFalse(Files.exists(PROJECT_ROOT.resolve(
                "sql/checks/userloginidentity_invalid_password_policy.sql")));
        assertNoPhysicalForeignKey(baseIdentitySchema);
    }

    @Test
    void profileLogicalRelationshipHasIndexedLookupAndExecutableOrphanCheck()
            throws IOException {
        String profileSchema =
                normalizedSql(readRequired(PROJECT_ROOT.resolve("sql/002_create_user_profile.sql")));
        String orphanCheck = normalizedSql(readRequired(
                PROJECT_ROOT.resolve("sql/checks/user_profile_orphans.sql")));

        assertTrue(profileSchema.contains("unique (login_identity_id)"));
        assertTrue(profileSchema.contains(
                "create index idx_user_profile_account_status_display_name_id "
                        + "on user_profile ( account_status asc, "
                        + "display_name asc nulls last, id asc )"));
        assertTrue(orphanCheck.contains("from user_profile up"));
        assertTrue(orphanCheck.contains(
                "left join userloginidentity uli on uli.id = up.login_identity_id"));
        assertTrue(orphanCheck.contains("where uli.id is null"));
        assertNoPhysicalForeignKey(orphanCheck);
    }

    @Test
    void membershipQuotaSchemaDefinesDefaultsConstraintsAndOrphanCheck()
            throws IOException {
        String membershipSchema = normalizedSql(readRequired(
                PROJECT_ROOT.resolve("sql/005_create_user_membership_quota.sql")));
        String membershipExpirationMigration = normalizedSql(readRequired(
                PROJECT_ROOT.resolve(
                        "sql/migrations/027_add_membership_expiration.sql")));
        String orphanCheck = normalizedSql(readRequired(PROJECT_ROOT.resolve(
                "sql/checks/user_membership_quota_orphans.sql")));

        assertTrue(membershipSchema.contains("membership_tier smallint not null default 0"));
        assertTrue(membershipSchema.contains(
                "quota_balance_minor bigint not null default 5000"));
        assertTrue(membershipSchema.contains("quota_period_started_at timestamptz"));
        assertTrue(membershipSchema.contains("quota_period_ends_at timestamptz"));
        assertTrue(membershipSchema.contains("membership_expires_at timestamptz"));
        assertTrue(membershipExpirationMigration.contains(
                "add column if not exists membership_expires_at timestamptz"));
        assertTrue(membershipExpirationMigration.contains(
                "comment on column user_membership_quota.membership_expires_at"));
        assertFalse(membershipSchema.contains("created_at"));
        assertFalse(membershipSchema.contains("updated_at"));
        assertFalse(membershipSchema.contains("set_user_membership_quota_updated_at"));
        assertFalse(membershipSchema.contains("trg_user_membership_quota_set_updated_at"));
        assertTrue(membershipSchema.contains("unique (login_identity_id)"));
        assertTrue(membershipSchema.contains("check (membership_tier between 0 and 6)"));
        assertTrue(membershipSchema.contains("check (quota_balance_minor >= 0)"));
        assertTrue(orphanCheck.contains("from user_membership_quota umq"));
        assertTrue(orphanCheck.contains(
                "left join userloginidentity uli on uli.id = umq.login_identity_id"));
        assertTrue(orphanCheck.contains("where uli.id is null"));
        assertNoPhysicalForeignKey(membershipSchema);
        assertNoPhysicalForeignKey(orphanCheck);
    }

    @Test
    void profileRelationshipDesignDocumentsCompensatingControlsAndAcceptedRisk()
            throws IOException {
        String design = readRequired(PROJECT_ROOT.resolve(
                "docs/database/user-profile-logical-relationship.md"));

        assertTrue(design.contains("写入验证"));
        assertTrue(design.contains("删除顺序"));
        assertTrue(design.contains("恢复方式"));
        assertTrue(design.contains("孤儿数据检查"));
        assertTrue(design.contains("接受风险"));
        assertTrue(design.contains("sql/checks/user_profile_orphans.sql"));
    }

    @Test
    void membershipQuotaRelationshipDocumentsCompensatingControlsAndAcceptedRisk()
            throws IOException {
        String design = readRequired(PROJECT_ROOT.resolve(
                "docs/database/user-membership-quota-logical-relationship.md"));

        assertTrue(design.contains("写入验证"));
        assertTrue(design.contains("删除顺序"));
        assertTrue(design.contains("恢复方式"));
        assertTrue(design.contains("孤儿数据检查"));
        assertTrue(design.contains("接受风险"));
        assertTrue(design.contains("sql/checks/user_membership_quota_orphans.sql"));
    }

    @Test
    void mapperXmlUsesBoundParametersAndBoundedContactConflictLookup()
            throws IOException {
        String identityMapper = normalizedSql(readRequired(PROJECT_ROOT.resolve(
                "ai-temperate-mapper/src/main/resources/mapper/user/identity/"
                        + "UserLoginIdentityMapper.xml")));
        String profileMapper = normalizedSql(readRequired(PROJECT_ROOT.resolve(
                "ai-temperate-mapper/src/main/resources/mapper/user/profile/UserProfileMapper.xml")));
        String membershipQuotaMapper = normalizedSql(readRequired(PROJECT_ROOT.resolve(
                "ai-temperate-mapper/src/main/resources/mapper/user/membership/"
                        + "UserMembershipQuotaMapper.xml")));

        assertFalse(identityMapper.contains("$" + "{"));
        assertFalse(profileMapper.contains("$" + "{"));
        assertFalse(membershipQuotaMapper.contains("$" + "{"));
        assertFalse(identityMapper.contains("select *"));

        // 用户资料边界夹具与批量查询允许 foreach 生成有界占位符，但所有实际值必须继续使用预编译参数绑定。
        assertTrue(profileMapper.contains("id=\"batchinsertboundaryfixtures\""));
        assertTrue(profileMapper.contains(
                "<foreach collection=\"profiles\" item=\"profile\" separator=\",\">"));
        assertTrue(profileMapper.contains("#{profile.loginidentityid"));
        assertTrue(profileMapper.contains("id=\"findbyloginidentityids\""));
        assertTrue(profileMapper.contains(
                "<foreach collection=\"loginidentityids\" item=\"loginidentityid\""));
        assertTrue(profileMapper.contains("#{loginidentityid,jdbctype=bigint}"));

        // 压测账号预检必须以一次有界批量查询完成；foreach 只生成占位符，禁止退化为逐条数据库 I/O 或字符串拼接。
        assertTrue(identityMapper.contains("id=\"findauthenticationbyids\""));
        assertTrue(identityMapper.contains(
                "<foreach collection=\"identityids\" item=\"identityid\""));
        assertTrue(identityMapper.contains("#{identityid,jdbctype=bigint}"));

        // 历史退款必须在一次有界 SQL 中批量处理；允许 foreach 生成行占位符，但每个值仍须使用预编译参数绑定。
        assertTrue(membershipQuotaMapper.contains("id=\"addhistoricalairefunds\""));
        assertTrue(membershipQuotaMapper.contains(
                "<foreach collection=\"candidates\" item=\"candidate\" separator=\",\">"));
        assertTrue(membershipQuotaMapper.contains("#{candidate.loginidentityid"));
        assertTrue(membershipQuotaMapper.contains("#{candidate.reservedquotaminor"));

        assertTrue(identityMapper.contains("id=\"findconflicts\""));
        assertTrue(identityMapper.contains("#{normalizedemail"));
        assertTrue(identityMapper.contains("lower(email) = #{normalizedemail"));
        assertTrue(identityMapper.contains("phone = #{normalizedphone"));
        assertTrue(identityMapper.contains("limit 2"));

        assertTrue(identityMapper.contains("id=\"findbynormalizedemail\""));
        assertTrue(identityMapper.contains("id=\"findbynormalizedphone\""));
        assertTrue(identityMapper.contains("id=\"insert\""));
        assertTrue(identityMapper.contains("insert into userloginidentity"));
        assertTrue(identityMapper.contains(
                "property=\"totpenabled\" column=\"totp_enabled\""));
        assertTrue(identityMapper.contains(
                "property=\"totpsecretencrypted\" column=\"totp_secret_encrypted\""));
        assertTrue(identityMapper.contains("id=\"updatepasswordhash\""));
        assertTrue(identityMapper.contains("password_hash = #{passwordhash"));
        assertTrue(identityMapper.contains("password_version = password_version + 1"));
        assertFalse(identityMapper.contains("password_strength_level"));
        assertFalse(identityMapper.contains("password_policy_version"));
        assertFalse(identityMapper.contains("email_verified_at"));
        assertFalse(identityMapper.contains("phone_verified_at"));
        assertFalse(identityMapper.contains("password_changed_at"));
        assertTrue(identityMapper.contains("where id = #{id"));
        assertTrue(identityMapper.contains("id=\"findcurrentuserprofilebyid\""));
        assertTrue(identityMapper.contains(
                "inner join user_membership_quota umq "
                        + "on umq.login_identity_id = uli.id"));
        assertTrue(identityMapper.contains("umq.membership_tier"));
        assertTrue(identityMapper.contains("umq.quota_balance_minor"));
        assertTrue(identityMapper.contains("umq.quota_period_started_at"));
        assertTrue(identityMapper.contains("umq.quota_period_ends_at"));

        assertTrue(profileMapper.contains("id=\"insert\""));
        assertTrue(profileMapper.contains("insert into user_profile"));
        assertTrue(profileMapper.contains("login_identity_id"));
        assertTrue(profileMapper.contains("display_name"));
        assertTrue(profileMapper.contains("#{loginidentityid"));
        assertTrue(profileMapper.contains("#{displayname"));
        assertFalse(profileMapper.contains("membership_tier"));

        assertTrue(membershipQuotaMapper.contains("id=\"insert\""));
        assertTrue(membershipQuotaMapper.contains("insert into user_membership_quota"));
        assertTrue(membershipQuotaMapper.contains("#{loginidentityid"));
        assertTrue(membershipQuotaMapper.contains("id=\"findbyloginidentityid\""));
        assertTrue(membershipQuotaMapper.contains(
                "id=\"expirepaidmembershipifdue\""));
        assertTrue(membershipQuotaMapper.contains("membership_tier between 1 and 6"));
        assertTrue(membershipQuotaMapper.contains("membership_expires_at is null"));
        assertTrue(membershipQuotaMapper.contains("membership_expires_at &lt;= #{now"));

        assertNoPhysicalForeignKey(identityMapper);
        assertNoPhysicalForeignKey(profileMapper);
        assertNoPhysicalForeignKey(membershipQuotaMapper);
    }

    private static String readRequired(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), () -> "Expected file to exist: " + path);
        return Files.readString(path);
    }

    private static String normalizedSql(String value) {
        return value.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void assertNoPhysicalForeignKey(String sql) {
        assertFalse(sql.matches("(?s).*\\bforeign\\s+key\\b.*"));
        assertFalse(sql.matches("(?s).*\\breferences\\b.*"));
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
