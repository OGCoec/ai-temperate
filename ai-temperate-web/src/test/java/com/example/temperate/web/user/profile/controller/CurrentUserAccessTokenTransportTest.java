package com.example.temperate.web.user.profile.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.model.user.domain.CurrentUserProfile;
import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.profile.CurrentUserProfileService;
import com.example.temperate.web.auth.interceptor.AccessTokenAuthenticationInterceptor;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
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
        when(profileService.getRequired(10001L)).thenReturn(new CurrentUserProfile(
                "Alice", "alice@example.test", "+14155550123"));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CurrentUserController(profileService))
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
                .andExpect(jsonPath("$.email").value("alice@example.test"));
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
