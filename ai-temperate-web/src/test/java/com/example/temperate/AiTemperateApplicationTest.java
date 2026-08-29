package com.example.temperate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.mapper.ai.AiConversationMessageMapper;
import com.example.temperate.mapper.ai.AiModelCapabilityMapper;
import com.example.temperate.mapper.ai.AiModelIconMapper;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.mapper.ai.AiModelApiUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelApiUsageMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.mapper.ai.AiModelUsageVideoDetailMapper;
import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.mapper.ai.ApiKeyUsageQueryMapper;
import com.example.temperate.mapper.ai.UserApiKeyMapper;
import com.example.temperate.mapper.ai.UserApiKeyModelMapper;
import com.example.temperate.mapper.audit.access.AccessRequestAuditMapper;
import com.example.temperate.mapper.user.avatar.UserAvatarMapper;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipPaymentCallbackMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.service.auth.session.authentication.dto.command.SessionBootstrapCommand;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.auth.device.service.GlobalDeviceBlockService;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.mailinspection.lease.MailInspectionJobLeaseService;
import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryPublisher;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTimingAspect;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.impl.NoOpAiConversationLifecycleDiagnosticServiceImpl;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import com.example.temperate.web.user.aiconversation.diagnostic.AiConversationRequestTraceFilter;
import com.example.temperate.web.edgeproxy.EdgeProxySignatureVerifier;
import com.example.temperate.web.edgeproxy.EdgeProxyVerificationResult;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

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
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @MockitoBean
    private ConnectionFactory rabbitConnectionFactory;

    @MockitoBean
    private SimpleRabbitListenerContainerFactoryConfigurer rabbitListenerConfigurer;

    @MockitoBean
    private AiConversationGenerationOutputStore aiConversationGenerationOutputStore;

    @MockitoBean
    private UserLoginIdentityMapper userLoginIdentityMapper;

    @MockitoBean
    private UserProfileMapper userProfileMapper;

    @MockitoBean
    private UserMembershipQuotaMapper userMembershipQuotaMapper;

    @MockitoBean
    private MembershipOrderMapper membershipOrderMapper;

    @MockitoBean
    private MembershipPaymentCallbackMapper membershipPaymentCallbackMapper;

    @MockitoBean
    private AiConversationMapper aiConversationMapper;

    @MockitoBean
    private AiConversationGenerationMapper aiConversationGenerationMapper;

    @MockitoBean
    private AiConversationGenerationPayloadMapper aiConversationGenerationPayloadMapper;

    @MockitoBean
    private AiConversationMessageMapper aiConversationMessageMapper;

    @MockitoBean
    private AiModelUsageMapper aiModelUsageMapper;

    @MockitoBean
    private AiModelUsageDetailMapper aiModelUsageDetailMapper;

    @MockitoBean
    private AiModelUsageVideoDetailMapper aiModelUsageVideoDetailMapper;

    @MockitoBean
    private AiModelMapper aiModelMapper;

    @MockitoBean
    private AiModelCapabilityMapper aiModelCapabilityMapper;

    @MockitoBean
    private AiModelIconMapper aiModelIconMapper;

    @MockitoBean
    private AiModelApiUsageMapper aiModelApiUsageMapper;

    @MockitoBean
    private AiModelApiUsageDetailMapper aiModelApiUsageDetailMapper;

    @MockitoBean
    private ApiKeyUsageQueryMapper apiKeyUsageQueryMapper;

    @MockitoBean
    private UserApiKeyMapper userApiKeyMapper;

    @MockitoBean
    private UserApiKeyModelMapper userApiKeyModelMapper;

    @MockitoBean
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private UserAvatarMapper userAvatarMapper;

    @MockitoBean
    private AccessRequestAuditMapper accessRequestAuditMapper;

    @MockitoBean
    private VerificationDeliveryPublisher verificationDeliveryPublisher;

    @MockitoBean
    private EdgeProxySignatureVerifier edgeProxySignatureVerifier;

    @MockitoBean
    private SessionAuthenticationService sessionAuthenticationService;

    @MockitoBean
    private GlobalDeviceBlockService globalDeviceBlockService;

    @MockitoBean
    private MailInspectionJobLeaseService mailInspectionJobLeaseService;

    @BeforeEach
    void allowUnblockedTestDevices() {
        when(globalDeviceBlockService.remainingBlockTtl(any())).thenReturn(Duration.ZERO);
    }

    @Test
    void healthEndpointIsPublicAndReportsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("ai-temperate"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void actuatorProbesArePublicAndHideInternalDetails() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void refusingTrafficChangesReadinessButKeepsLivenessUp() throws Exception {
        IllegalStateException cause = new IllegalStateException(
                "test-only-readiness-transition");
        AvailabilityChangeEvent.publish(
                applicationContext,
                cause,
                ReadinessState.REFUSING_TRAFFIC);
        try {
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"))
                    .andExpect(jsonPath("$.components").doesNotExist());
            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        } finally {
            // 测试上下文会被同一测试类复用，必须恢复就绪状态，避免污染后续无关用例。
            AvailabilityChangeEvent.publish(
                    applicationContext,
                    cause,
                    ReadinessState.ACCEPTING_TRAFFIC);
        }
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
        AiConversationStreamFailureDiagnosticService diagnosticService =
                applicationContext.getBean(AiConversationStreamFailureDiagnosticService.class);
        assertThat(AopUtils.isAopProxy(diagnosticService)).isTrue();
        assertThat(applicationContext.getBeansOfType(
                AiConversationLifecycleDiagnosticService.class))
                .hasSize(1)
                .allSatisfy((name, service) -> assertThat(service)
                        .isInstanceOf(
                                NoOpAiConversationLifecycleDiagnosticServiceImpl.class));
        assertThat(applicationContext.getBeansOfType(
                AiConversationLifecycleTimingAspect.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(
                AiConversationRequestTraceFilter.class)).isEmpty();
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
                .andExpect(result -> {
                    Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
                    assertThat(cookie).isNotNull();
                    assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
                });
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
        allowTrustedBrowserEdgeRequest();
        when(sessionAuthenticationService.bootstrap(any(SessionBootstrapCommand.class)))
                .thenThrow(new SessionAuthenticationException(
                        SessionAuthenticationErrorCode.REFRESH_TOKEN_REQUIRED,
                        "Refresh token is required.",
                        true));
        mockMvc.perform(post("/api/auth/session/bootstrap")
                        .header("X-Client-Platform", "H5")
                        .header("X-Device-Installation-Id", "device-1")
                        .header("Origin", "http://localhost:5173")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REQUIRED"));
    }

    @Test
    void bootstrapRejectsCrossSiteFetchMetadataEvenThoughItIsHeaderExempt() throws Exception {
        allowTrustedBrowserEdgeRequest();
        mockMvc.perform(post("/api/auth/session/bootstrap")
                        .header("X-Client-Platform", "H5")
                        .header("X-Device-Installation-Id", "device-1")
                        .header("Origin", "http://localhost:5173")
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    private void allowTrustedBrowserEdgeRequest() {
        when(edgeProxySignatureVerifier.hasAnyEdgeHeader(any())).thenReturn(true);
        when(edgeProxySignatureVerifier.verify(any())).thenReturn(
                new EdgeProxyVerificationResult("v2", "niko000o.site", null));
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
