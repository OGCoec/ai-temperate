package com.example.temperate.web.admin.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.config.properties.AdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 验证管理员业务 Cookie 默认保持 Host-only，并保留迁移窗口的独立 Domain 绑定。
 */
class AdminPropertiesTest {

    @Test
    void bindsAnExplicitMigrationCsrfDomainWithoutSharingSensitiveCookies() {
        contextRunner("", "niko000o.site").run(context -> {
            assertThat(context).hasNotFailed();
            AdminProperties.Cookies cookies = context.getBean(AdminProperties.class).cookies();

            assertThat(cookies.domain()).isEmpty();
            assertThat(cookies.csrfDomain()).isEqualTo("niko000o.site");
        });
    }

    @Test
    void doesNotReuseSensitiveCookieDomainWhenNoDedicatedCsrfDomainIsConfigured() {
        contextRunner("niko000o.site").run(context -> {
            assertThat(context).hasNotFailed();
            AdminProperties.Cookies cookies = context.getBean(AdminProperties.class).cookies();

            assertThat(cookies.domain()).isEqualTo("niko000o.site");
            assertThat(cookies.csrfDomain()).isEmpty();
        });
    }

    @Test
    void keepsAllAdministratorCookiesHostOnlyWhenBothDomainsAreEmpty() {
        contextRunner("", "").run(context -> {
            assertThat(context).hasNotFailed();
            AdminProperties.Cookies cookies = context.getBean(AdminProperties.class).cookies();

            assertThat(cookies.domain()).isEmpty();
            assertThat(cookies.csrfDomain()).isEmpty();
        });
    }

    @Test
    void rejectsDirectNonHostOnlyConfigurationContainingInvalidDomain() {
        for (String invalid : new String[] {
                "https://niko000o.site",
                "niko000o.site:443",
                "niko000o.site/admin"
        }) {
            baseContextRunner()
                    .withPropertyValues("app.admin.cookies.csrf-domain=" + invalid)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining(
                                        "Administrator cookie domains must be empty or valid hostnames");
                    });
        }
    }

    private static ApplicationContextRunner contextRunner(String domain) {
        return baseContextRunner()
                .withPropertyValues("ADMIN_COOKIE_DOMAIN=" + domain);
    }

    private static ApplicationContextRunner contextRunner(
            String domain,
            String csrfDomain) {
        return contextRunner(domain)
                .withPropertyValues("ADMIN_CSRF_COOKIE_DOMAIN=" + csrfDomain);
    }

    private static ApplicationContextRunner baseContextRunner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "ADMIN_SESSION_HMAC_SECRET_BASE64="
                                + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                        "HCAPTCHA_SITE_KEY=10000000-ffff-ffff-ffff-000000000001",
                        "HCAPTCHA_SECRET_KEY=0x0000000000000000000000000000000000000000");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminProperties.class)
    static class TestConfiguration {
    }
}
