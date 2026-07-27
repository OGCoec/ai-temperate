package com.example.temperate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.mapper.ai.AiModelCapabilityMapper;
import com.example.temperate.mapper.ai.AiModelIconMapper;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.mapper.user.avatar.UserAvatarMapper;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPublisher;
import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验证 Web 应用 Spring 上下文、认证路由和基础安全装配的集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiTemperateApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AuthSecurityProperties securityProperties;

    @Autowired
    private AdminProperties adminProperties;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private UserLoginIdentityMapper userLoginIdentityMapper;

    @MockitoBean
    private UserProfileMapper userProfileMapper;

    @MockitoBean
    private UserMembershipQuotaMapper userMembershipQuotaMapper;

    @MockitoBean
    private AiModelMapper aiModelMapper;

    @MockitoBean
    private AiModelCapabilityMapper aiModelCapabilityMapper;

    @MockitoBean
    private AiModelIconMapper aiModelIconMapper;

    @MockitoBean
    private UserAvatarMapper userAvatarMapper;

    @MockitoBean
    private VerificationDeliveryPublisher verificationDeliveryPublisher;

    @Test
    void healthEndpointIsPublicAndReportsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("ai-temperate"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void bindsValidatedTestInfrastructureAndHasSingletonBeans() {
        assertThat(securityProperties.env()).isEqualTo(AuthSecurityProperties.Env.TEST);
        assertThat(securityProperties.cors().allowedOrigins())
                .containsExactly("http://localhost:5173");
        assertThat(adminProperties.cookies().domain()).isEmpty();
        assertThat(adminProperties.cookies().csrfDomain()).isEmpty();
        assertThat(applicationContext.getBeansOfType(PasswordEncoder.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(Clock.class))
                .containsOnlyKeys("applicationClock");
    }

    @Test
    void verificationDeliveryLogLevelsParseForMainAndLocalDevelopmentProfiles()
            throws Exception {
        String loggerName =
                "logging.level.com.example.temperate.service.registration.verification";
        assertThat(yamlProperty("application.yml", loggerName)).isEqualTo("INFO");
        assertThat(yamlProperty("application-local-dev.yml", loggerName)).isEqualTo("DEBUG");
    }

    @Test
    void localHttpsProfileTrustsOnlyLoopbackProxyByDefault() throws Exception {
        assertThat(yamlProperty(
                        "application-local-https.yml",
                        "app.phone-country.trusted-proxy-ranges"))
                .isEqualTo("127.0.0.1/32,::1/128");
    }

    private static String yamlProperty(String resourceName, String propertyName)
            throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load(resourceName, new ClassPathResource(resourceName))
                .forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources).getProperty(propertyName);
    }

    @Test
    void doesNotCreateDefaultInMemoryUsers() {
        assertThat(applicationContext.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
    }

    @Test
    void basicCredentialsCannotAccessProtectedResources() throws Exception {
        PasswordEncoder passwordEncoder = applicationContext.getBean(PasswordEncoder.class);
        applicationContext.getBeansOfType(InMemoryUserDetailsManager.class).values().forEach(manager ->
                manager.createUser(User.withUsername("review-probe")
                        .password(passwordEncoder.encode("test-password-only"))
                        .roles("USER")
                        .build()));

        mockMvc.perform(get("/api/protected-review-probe")
                        .with(httpBasic("review-probe", "test-password-only")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void h5UnsafeRequestsWithoutCsrfReturnTheStableForbiddenError() throws Exception {
        mockMvc.perform(post("/api/auth/login/password")
                        .header("X-Device-Installation-Id", "device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void h5UnsafeRequestsRejectForgedCsrfHeaders() throws Exception {
        mockMvc.perform(post("/api/auth/login/password")
                        .header("X-Device-Installation-Id", "device-1")
                        .header("X-CSRF-Token", "forged-csrf")
                        .cookie(new Cookie("XSRF-TOKEN", "cookie-csrf"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void csrfEndpointCreatesTheReadableSessionCookie() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Set-Cookie", containsString("XSRF-TOKEN=")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")));
    }

    @Test
    void androidCannotUseTheBrowserCsrfEndpointOrReceiveItsCookie() throws Exception {
        mockMvc.perform(get("/api/auth/csrf")
                        .header("X-Client-Platform", "ANDROID"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void bootstrapIsCsrfExemptButStillRequiresTheH5SessionBoundary() throws Exception {
        mockMvc.perform(post("/api/auth/session/bootstrap")
                        .header("X-Device-Installation-Id", "device-1")
                        .header("Origin", "http://localhost:5173")
                        .header("Sec-Fetch-Site", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REQUIRED"));
    }

    @Test
    void bootstrapRejectsAnIllegalBrowserOriginEvenThoughItIsHeaderExempt() throws Exception {
        mockMvc.perform(post("/api/auth/session/bootstrap")
                        .header("X-Device-Installation-Id", "device-1")
                        .header("Origin", "https://attacker.example")
                        .header("Sec-Fetch-Site", "cross-site")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void androidUnsafeRequestsDoNotUseBrowserCsrf() throws Exception {
        mockMvc.perform(post("/api/auth/login/password")
                        .header("X-Client-Platform", "ANDROID")
                        .header("X-Device-Installation-Id", "device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
