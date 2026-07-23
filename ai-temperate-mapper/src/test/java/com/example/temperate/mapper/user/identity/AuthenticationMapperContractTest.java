package com.example.temperate.mapper.user.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

/**
 * 验证认证查询映射返回的字段与认证领域模型契约一致。
 */
class AuthenticationMapperContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void authenticationContextIsImmutableAndDoesNotExposePasswordHashInToString() {
        AuthenticationContext context = new AuthenticationContext(
                10001L, "{bcrypt}sensitive-hash", 7L, AccountStatus.ACTIVE, "Temperate User");

        assertThat(context.getIdentityId()).isEqualTo(10001L);
        assertThat(context.getPasswordVersion()).isEqualTo(7L);
        assertThat(context.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(context.toString()).doesNotContain("sensitive-hash");
        assertThat(AuthenticationContext.class.getDeclaredFields())
                .allMatch(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers()));
        assertThat(AuthenticationContext.class.getDeclaredFields())
                .noneMatch(field -> "membershipTier".equals(field.getName())
                        || "quotaBalanceMinor".equals(field.getName())
                        || "passwordStrengthLevel".equals(field.getName())
                        || "passwordPolicyVersion".equals(field.getName()));
    }

    @Test
    void mapperExposesSingleQueryAuthenticationLookupsAndHashUpgradeCas() throws Exception {
        Class<UserLoginIdentityMapper> mapper = UserLoginIdentityMapper.class;

        Method byEmail = requiredMethod(mapper,
                "findAuthenticationByNormalizedEmail", String.class);
        Method byPhone = requiredMethod(mapper,
                "findAuthenticationByNormalizedPhone", String.class);
        Method byId = requiredMethod(mapper, "findAuthenticationById", long.class);
        Method cas = requiredMethod(mapper,
                "upgradePasswordHashCas", long.class, String.class, String.class);

        assertThat(byEmail.getReturnType()).isEqualTo(AuthenticationContext.class);
        assertThat(byPhone.getReturnType()).isEqualTo(AuthenticationContext.class);
        assertThat(byId.getReturnType()).isEqualTo(AuthenticationContext.class);
        assertThat(cas.getReturnType()).isEqualTo(int.class);
        assertParam(byEmail, 0, "normalizedEmail");
        assertParam(byPhone, 0, "normalizedPhone");
        assertParam(byId, 0, "identityId");
        assertParam(cas, 0, "identityId");
        assertParam(cas, 1, "expectedPasswordHash");
        assertParam(cas, 2, "upgradedPasswordHash");
    }

    @Test
    void mapperXmlJoinsProfileOnceAndCasDoesNotChangePasswordSemantics() throws Exception {
        String xml = normalized(Files.readString(PROJECT_ROOT.resolve(
                "ai-temperate-mapper/src/main/resources/mapper/user/identity/UserLoginIdentityMapper.xml")));

        assertThat(xml).contains("id=\"authenticationcontextresultmap\"");
        assertThat(xml).contains("id=\"findauthenticationbynormalizedemail\"");
        assertThat(xml).contains("id=\"findauthenticationbynormalizedphone\"");
        assertThat(xml).contains("id=\"findauthenticationbyid\"");
        assertThat(xml).contains("left join user_profile up on up.login_identity_id = uli.id");
        assertThat(xml).contains("uli.password_version");
        assertThat(xml).contains("when 0 then 'active'");
        assertThat(xml).contains("when 1 then 'frozen'");
        assertThat(xml).contains("when 2 then 'disabled'");
        assertThat(xml).contains("id=\"upgradepasswordhashcas\"");
        assertThat(xml).contains("password_hash = #{upgradedpasswordhash");
        assertThat(xml).contains("and password_hash = #{expectedpasswordhash");

        String casSql = xml.substring(xml.indexOf("id=\"upgradepasswordhashcas\""));
        assertThat(casSql).doesNotContain(
                "password_version =", "password_version +");
        assertThat(xml).doesNotContain(
                "email_verified_at", "phone_verified_at", "password_changed_at",
                "password_strength_level", "password_policy_version");
        assertThat(xml).doesNotContain("membership_tier", "user_membership_quota");
        assertThat(xml).doesNotContain("select *", "${");
    }

    @Test
    void baseSchemaDefinesPositivePasswordVersionAndProvidesCheck() throws Exception {
        String baseSchema = normalized(Files.readString(PROJECT_ROOT.resolve(
                "sql/001_create_users.sql")));
        String check = normalized(Files.readString(PROJECT_ROOT.resolve(
                "sql/checks/userloginidentity_invalid_password_version.sql")));

        assertThat(baseSchema).contains(
                "password_version bigint not null default 1",
                "constraint chk_userloginidentity_password_version_positive",
                "check (password_version > 0)");
        assertThat(baseSchema).doesNotContain(
                "alter table userloginidentity",
                "email_verified_at",
                "phone_verified_at",
                "password_changed_at",
                "account_status");
        assertThat(PROJECT_ROOT.resolve(
                "sql/004_add_userloginidentity_verification_timestamps.sql"))
                .doesNotExist();
        assertThat(PROJECT_ROOT.resolve(
                "sql/005_add_userloginidentity_password_version.sql"))
                .doesNotExist();
        assertThat(PROJECT_ROOT.resolve(
                "sql/rollback/005_remove_userloginidentity_password_version.sql"))
                .doesNotExist();
        assertThat(check).contains("password_version <= 0");
        assertNoPhysicalForeignKey(baseSchema);
    }

    private static void assertParam(Method method, int index, String expected) {
        Param param = (Param) java.util.Arrays.stream(method.getParameterAnnotations()[index])
                .filter(Param.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(param.value()).isEqualTo(expected);
    }

    private static Method requiredMethod(
            Class<?> type, String name, Class<?>... parameterTypes) {
        Method[] result = new Method[1];
        assertThatCode(() -> result[0] = type.getMethod(name, parameterTypes))
                .as("expected method %s.%s", type.getName(), name)
                .doesNotThrowAnyException();
        return result[0];
    }

    private static String normalized(String text) {
        return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void assertNoPhysicalForeignKey(String sql) {
        assertThat(sql).doesNotMatch("(?s).*\\bforeign\\s+key\\b.*");
        assertThat(sql).doesNotMatch("(?s).*\\breferences\\b.*");
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-mapper"))
                    && Files.isDirectory(current.resolve("sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
