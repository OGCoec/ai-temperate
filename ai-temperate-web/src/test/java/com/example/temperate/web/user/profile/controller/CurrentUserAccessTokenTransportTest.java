package com.example.temperate.web.user.profile.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.profile.CurrentUserProfileResult;
import com.example.temperate.service.user.profile.CurrentUserProfileService;
import com.example.temperate.web.auth.interceptor.AccessTokenAuthenticationInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 验证 H5 HttpOnly Cookie AT 与 Android Bearer AT 都通过统一拦截器建立 SecurityContext 后访问个人资料接口。
 */
class CurrentUserAccessTokenTransportTest {

    private AccessSessionService accessSessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accessSessionService = mock(AccessSessionService.class);
        CurrentUserProfileService profileService = mock(CurrentUserProfileService.class);
        when(accessSessionService.authenticate("valid-access-token"))
                .thenReturn(new SessionPrincipal(10001L, "AAAAAAAAJxE", "Alice"));
        when(profileService.getRequired(10001L)).thenReturn(new CurrentUserProfileResult(
                "Alice",
                "alice@example.test",
                "+14155550123",
                null,
                MembershipTier.FREE,
                "5000",
                "50.0",
                "5000",
                "50.0",
                "0",
                "0.0",
                "0.0",
                null,
                OffsetDateTime.parse("2026-08-06T12:00:00Z")));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CurrentUserController(profileService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addInterceptors(new AccessTokenAuthenticationInterceptor(accessSessionService))
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void h5UsesTheAccessTokenCookie() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("X-Client-Platform", "H5")
                        .cookie(new Cookie("access_token", "valid-access-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.test"))
                .andExpect(jsonPath("$.avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.membershipTier").value("FREE"))
                .andExpect(jsonPath("$.quotaBalanceMinor").value("5000"))
                .andExpect(jsonPath("$.quotaBalance").value("50.0"))
                .andExpect(jsonPath("$.quotaTotal").value("50.0"))
                .andExpect(jsonPath("$.quotaUsed").value("0.0"))
                .andExpect(jsonPath("$.quotaUsagePercent").value("0.0"))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void androidUsesTheBearerAccessToken() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("X-Client-Platform", "ANDROID")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+14155550123"));
    }
}
