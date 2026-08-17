package com.example.temperate.web.user.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.CreateCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Created;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Detail;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Status;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Summary;
import com.example.temperate.service.user.apikey.management.UserApiKeyService;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

/**
 * 该契约测试是来确保 API Key 管理 Controller 能被方法校验切面代理，防止 final 类型导致应用上下文启动失败。
 */
final class CurrentUserApiKeyControllerContractTest {

    private static final SessionPrincipal PRINCIPAL =
            new SessionPrincipal(7L, "ARCRqojCEAA", "API Key Test User");

    @Test
    void remainsProxyableForMethodValidation() {
        assertThat(Modifier.isFinal(CurrentUserApiKeyController.class.getModifiers()))
                .isFalse();
    }

    @Test
    void createRequiresCanonicalUuidV4AndPassesItToTheService() {
        UserApiKeyService service = mock(UserApiKeyService.class);
        OffsetDateTime now = OffsetDateTime.parse("2026-08-17T12:00:00Z");
        Summary summary = new Summary(
                "AAAAAAAAAAE",
                "sk-…Ab3D",
                Status.ENABLED,
                null,
                false,
                null,
                now,
                now,
                0L);
        when(service.create(anyLong(), any())).thenReturn(
                new Created(new Detail(summary, List.of()), "sk-" + "A".repeat(86)));
        CurrentUserApiKeyController controller = new CurrentUserApiKeyController(service);
        var request = new CurrentUserApiKeyController.CreateRequest(
                null,
                List.of("ARRtIXbCEAA"));

        assertThatThrownBy(() -> controller.create(PRINCIPAL, null, request))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(failure -> ((ApiKeyManagementException) failure).code())
                .isEqualTo(ApiKeyManagementErrorCode.IDEMPOTENCY_KEY_INVALID);
        assertThatThrownBy(() -> controller.create(
                PRINCIPAL,
                "018f7777-2d11-7abc-8def-0123456789ab",
                request))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(failure -> ((ApiKeyManagementException) failure).code())
                .isEqualTo(ApiKeyManagementErrorCode.IDEMPOTENCY_KEY_INVALID);
        assertThatThrownBy(() -> controller.create(
                PRINCIPAL,
                "550E8400-E29B-41D4-A716-446655440000",
                request))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(failure -> ((ApiKeyManagementException) failure).code())
                .isEqualTo(ApiKeyManagementErrorCode.IDEMPOTENCY_KEY_INVALID);

        controller.create(
                PRINCIPAL,
                "550e8400-e29b-41d4-a716-446655440000",
                request);

        ArgumentCaptor<CreateCommand> command = ArgumentCaptor.forClass(CreateCommand.class);
        verify(service).create(anyLong(), command.capture());
        assertThat(command.getValue().idempotencyKey().toString())
                .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }
}
