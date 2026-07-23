package com.example.temperate.service.auth.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

/**
 * 验证认证业务层接口、实现分层和安全边界的架构契约。
 */
class AuthenticationBusinessContractTest {

    private static final Path PROJECT_ROOT = findProjectRoot();

    @Test
    void loginAndSessionAuthenticationUseInterfaceAndFinalImplStructure() {
        assertServiceContract(
                "com.example.temperate.service.auth.login.service.LoginService",
                "com.example.temperate.service.auth.login.service.impl.LoginServiceImpl");
        assertServiceContract(
                "com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService",
                "com.example.temperate.service.auth.session.authentication.service.impl.SessionAuthenticationServiceImpl");
        assertServiceContract(
                "com.example.temperate.service.auth.login.limit.service.LoginRateLimitService",
                "com.example.temperate.service.auth.login.limit.service.impl.LoginRateLimitServiceImpl");
    }

    @Test
    void authenticationPersistenceAndAtomicRedisAssetsExist() {
        assertClassExists("com.example.temperate.model.auth.domain.AuthenticationContext");
        assertClassExists("com.example.temperate.model.auth.enums.AccountStatus");
        assertThat(PROJECT_ROOT.resolve("sql/001_create_users.sql"))
                .isRegularFile();
        assertThat(PROJECT_ROOT.resolve(
                        "ai-temperate-service/src/main/resources/lua/auth-login/check_login_limit.lua"))
                .isRegularFile();
        assertThat(PROJECT_ROOT.resolve(
                        "ai-temperate-service/src/main/resources/lua/auth-login/record_login_failure.lua"))
                .isRegularFile();
        assertThat(PROJECT_ROOT.resolve(
                        "ai-temperate-service/src/main/resources/lua/auth-login/clear_login_failures.lua"))
                .isRegularFile();
    }

    @Test
    void loginSupportComponentsExistWithExplicitLayering() {
        assertClassExists("com.example.temperate.service.auth.login.component.normalizer.LoginInputNormalizer");
        assertClassExists("com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector");
        assertClassExists(
                "com.example.temperate.service.auth.login.limit.store.impl.RedisLoginFailureStore");
        assertClassExists("com.example.temperate.service.auth.login.audit.observer.LoginAuditObserver");
        assertClassExists(
                "com.example.temperate.service.auth.login.audit.observer.impl.MicrometerLoginAuditObserver");
        assertClassExists(
                "com.example.temperate.service.auth.config.AuthSessionInfrastructureConfiguration");
    }

    @Test
    void authSessionConfigurationIsExplicitInProductionAndTestYaml() throws Exception {
        String production = Files.readString(PROJECT_ROOT.resolve(
                "ai-temperate-web/src/main/resources/application.yml"));
        String test = Files.readString(PROJECT_ROOT.resolve(
                "ai-temperate-web/src/test/resources/application-test.yml"));

        assertThat(production).contains("auth-session:", "login-limit:");
        assertThat(test).contains("auth-session:", "login-limit:");
    }

    @Test
    void productionUsesJdkInterfaceProxiesForFinalServiceImplementations() throws Exception {
        String production = Files.readString(PROJECT_ROOT.resolve(
                "ai-temperate-web/src/main/resources/application.yml"));

        assertThat(production).contains(
                "proxy-target-class: ${SPRING_AOP_PROXY_TARGET_CLASS:false}");
    }

    @Test
    void localHttpsProfileKeepsKeyStoreCredentialsOutsideYaml() throws Exception {
        Path profilePath = PROJECT_ROOT.resolve(
                "ai-temperate-web/src/main/resources/application-local-https.yml");
        String profile = Files.readString(profilePath);

        assertThat(profilePath).isRegularFile();
        assertThat(profile)
                .contains(
                        "key-store: ${SERVER_SSL_KEY_STORE}",
                        "key-store-password: ${SERVER_SSL_KEY_STORE_PASSWORD}",
                        "key-store-type: ${SERVER_SSL_KEY_STORE_TYPE:PKCS12}",
                        "key-alias: ${SERVER_SSL_KEY_ALIAS:ai-temperate-local}")
                .doesNotContain("changeit", "local-https.password.dpapi");
    }

    @Test
    void localHttpsProfileIsIsolatedFromDefaultRuntimeConfiguration() throws Exception {
        String production = Files.readString(PROJECT_ROOT.resolve(
                "ai-temperate-web/src/main/resources/application.yml"));
        String profile = Files.readString(PROJECT_ROOT.resolve(
                "ai-temperate-web/src/main/resources/application-local-https.yml"));

        assertThat(production)
                .doesNotContain("SERVER_SSL_KEY_STORE", "SERVER_SSL_KEY_STORE_PASSWORD");
        assertThat(profile)
                .contains(
                        "address: 127.0.0.1",
                        "enabled: true",
                        "enabled-protocols: TLSv1.3,TLSv1.2",
                        "env: LOCAL",
                        "environment: LOCAL");
    }

    private static void assertServiceContract(String interfaceName, String implementationName) {
        Class<?> serviceInterface = load(interfaceName);
        Class<?> implementation = load(implementationName);

        assertThat(serviceInterface).isInterface();
        assertThat(implementation.getAnnotation(Service.class)).isNotNull();
        assertThat(Modifier.isFinal(implementation.getModifiers())).isTrue();
        assertThat(serviceInterface.isAssignableFrom(implementation)).isTrue();
        assertThat(implementation.getDeclaredFields())
                .allMatch(field -> Modifier.isFinal(field.getModifiers()));
    }

    private static void assertClassExists(String className) {
        assertThatCode(() -> Class.forName(className))
                .as("expected class %s", className)
                .doesNotThrowAnyException();
    }

    private static Class<?> load(String className) {
        final Class<?>[] loaded = new Class<?>[1];
        assertThatCode(() -> loaded[0] = Class.forName(className))
                .as("expected class %s", className)
                .doesNotThrowAnyException();
        return loaded[0];
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("ai-temperate-service"))
                    && Files.isDirectory(current.resolve("sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate ai-temperate project root");
    }
}
