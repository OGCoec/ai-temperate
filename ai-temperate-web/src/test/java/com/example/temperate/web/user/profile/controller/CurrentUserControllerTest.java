package com.example.temperate.web.user.profile.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.model.user.domain.CurrentUserProfile;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.profile.CurrentUserProfileService;
import com.example.temperate.web.user.profile.api.CurrentUserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/**
 * 验证个人资料接口只使用认证主体中的用户 ID，并返回无缓存的最小资料响应。
 */
class CurrentUserControllerTest {

    @Test
    void returnsOnlyDisplayNameEmailAndPhoneWithoutCaching() {
        CurrentUserProfileService service = mock(CurrentUserProfileService.class);
        when(service.getRequired(10001L)).thenReturn(new CurrentUserProfile(
                "Alice", "alice@example.test", "+14155550123"));
        CurrentUserController controller = new CurrentUserController(service);
        SessionPrincipal principal = new SessionPrincipal(10001L, "AAAAAAAAJxE", "Alice");

        ResponseEntity<CurrentUserResponse> response = controller.me(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getCacheControl())
                .contains(CacheControl.noStore().getHeaderValue())
                .contains("private");
        assertThat(response.getBody()).isEqualTo(new CurrentUserResponse(
                "Alice", "alice@example.test", "+14155550123"));
        verify(service).getRequired(10001L);
    }
}
