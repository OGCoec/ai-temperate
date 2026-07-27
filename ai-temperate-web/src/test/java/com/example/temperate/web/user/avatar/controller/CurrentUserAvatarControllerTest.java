package com.example.temperate.web.user.avatar.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.avatar.AvatarConfirmation;
import com.example.temperate.service.user.avatar.AvatarImageFormat;
import com.example.temperate.service.user.avatar.AvatarPreupload;
import com.example.temperate.service.user.avatar.UserAvatarService;
import com.example.temperate.web.user.avatar.api.ConfirmAvatarRequest;
import com.example.temperate.web.user.avatar.api.CreateAvatarPreuploadRequest;
import jakarta.validation.constraints.Pattern;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

/**
 * 验证头像 API 只把认证主体中的用户标识传给业务层，不接受客户端 Object Key、Bucket 或用户 ID。
 */
class CurrentUserAvatarControllerTest {

    private static final SessionPrincipal PRINCIPAL =
            new SessionPrincipal(10001L, "AAAAAAAAJxE", "Alice");

    @Test
    void supportsValidatedCglibProxy() {
        ProxyFactory proxyFactory = new ProxyFactory(
                new CurrentUserAvatarController(mock(UserAvatarService.class)));
        proxyFactory.setProxyTargetClass(true);

        assertThat(proxyFactory.getProxy())
                .isInstanceOf(CurrentUserAvatarController.class);
    }

    @Test
    void createsPreuploadForAuthenticatedUserAndReturnsCreated() {
        UserAvatarService service = mock(UserAvatarService.class);
        AvatarPreupload preupload = new AvatarPreupload(
                "0123456789_abcdefghijklm",
                "https://signed.example/upload",
                "PUT",
                Map.of("Content-Type", "image/webp"),
                Instant.parse("2026-07-26T12:10:00Z"));
        when(service.createPreupload(
                        10001L, "AAAAAAAAJxE", AvatarImageFormat.WEBP, 428716L))
                .thenReturn(preupload);
        CurrentUserAvatarController controller = new CurrentUserAvatarController(service);

        var response = controller.createPreupload(
                PRINCIPAL,
                new CreateAvatarPreuploadRequest(AvatarImageFormat.WEBP, 428716L));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().preuploadId()).isEqualTo(preupload.preuploadId());
    }

    @Test
    void confirmsUsingOnlyAuthenticatedUserAndPathImageId() {
        UserAvatarService service = mock(UserAvatarService.class);
        String imageId = "0123456789_abcdefghijklm";
        when(service.confirm(10001L, "AAAAAAAAJxE", imageId, AvatarImageFormat.PNG))
                .thenReturn(new AvatarConfirmation("https://cdn.example.test/avatar.png"));
        CurrentUserAvatarController controller = new CurrentUserAvatarController(service);

        var response = controller.confirm(
                PRINCIPAL,
                imageId,
                new ConfirmAvatarRequest(AvatarImageFormat.PNG));

        assertThat(response.getBody().avatarUrl())
                .isEqualTo("https://cdn.example.test/avatar.png");
        verify(service).confirm(10001L, "AAAAAAAAJxE", imageId, AvatarImageFormat.PNG);
    }

    @Test
    void requiresTwentyFourCharacterNanoIdForAvatarPathVariables() throws Exception {
        Method cancel = CurrentUserAvatarController.class.getMethod(
                "cancel",
                SessionPrincipal.class,
                String.class,
                AvatarImageFormat.class);
        Method confirm = CurrentUserAvatarController.class.getMethod(
                "confirm",
                SessionPrincipal.class,
                String.class,
                ConfirmAvatarRequest.class);

        assertThat(cancel.getParameters()[1].getAnnotation(Pattern.class).regexp())
                .isEqualTo("^[A-Za-z0-9_-]{24}$");
        assertThat(confirm.getParameters()[1].getAnnotation(Pattern.class).regexp())
                .isEqualTo("^[A-Za-z0-9_-]{24}$");
    }
}
