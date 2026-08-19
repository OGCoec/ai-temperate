package com.example.temperate.web.user.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Created;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Detail;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.ModelGrant;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Page;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Status;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Summary;
import com.example.temperate.service.user.apikey.management.UserApiKeyService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 该测试是来约束 API Key 管理响应在源站统一禁止缓存和表示转换，并确保乐观锁响应始终携带强 ETag。
 */
final class CurrentUserApiKeyControllerResponsePolicyTest {

    private static final String CDN_CACHE_CONTROL = "CDN-Cache-Control";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.of(
            2026, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime EXPIRES_AT = CREATED_AT.plusDays(7);
    private static final SessionPrincipal PRINCIPAL =
            new SessionPrincipal(7L, "ARCRqojCEAA", "API Key Test User");
    private static final byte[] API_KEY_INTERNAL_ID = {
            0x01, (byte) 0x9b, 0x12, 0x34, 0x56, 0x78, 0x01, 0x02,
            0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a
    };
    private static final ApiKeyPublicId API_KEY_ID =
            new ApiKeyPublicId("01KC938NKR041061050R3GG28A", API_KEY_INTERNAL_ID);
    private static final String IDEMPOTENCY_KEY =
            "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void allManagementResponsesDisableCachingAndRepresentationTransforms() {
        UserApiKeyService service = service();
        CurrentUserApiKeyController controller = new CurrentUserApiKeyController(service);

        List<ResponseEntity<?>> responses = List.of(
                controller.create(
                        PRINCIPAL,
                        IDEMPOTENCY_KEY,
                        new CurrentUserApiKeyController.CreateRequest(
                                EXPIRES_AT,
                                List.of("ARRtIXbCEAA"))),
                controller.list(PRINCIPAL, null, 20),
                controller.detail(PRINCIPAL, API_KEY_ID),
                controller.update(
                        PRINCIPAL,
                        API_KEY_ID,
                        "\"v0\"",
                        new CurrentUserApiKeyController.UpdateRequest(
                                Status.ENABLED,
                                EXPIRES_AT)),
                controller.replaceModels(
                        PRINCIPAL,
                        API_KEY_ID,
                        "\"v0\"",
                        new CurrentUserApiKeyController.ReplaceModelsRequest(
                                List.of("ARRtIXbCEAA"))),
                controller.delete(PRINCIPAL, API_KEY_ID, "\"v0\""));

        for (ResponseEntity<?> response : responses) {
            assertThat(response.getHeaders().getCacheControl())
                    .contains("no-store")
                    .contains("private")
                    .contains("no-transform");
            assertThat(response.getHeaders().getFirst(CDN_CACHE_CONTROL))
                    .isEqualTo("no-store");
        }
    }

    @Test
    void versionedRepresentationsUseStrongEtagsWhileListAndDeleteOmitThem() {
        UserApiKeyService service = service();
        CurrentUserApiKeyController controller = new CurrentUserApiKeyController(service);

        assertThat(controller.create(
                PRINCIPAL,
                IDEMPOTENCY_KEY,
                new CurrentUserApiKeyController.CreateRequest(
                        EXPIRES_AT,
                        List.of("ARRtIXbCEAA"))).getHeaders().getETag())
                .isEqualTo("\"v0\"");
        assertThat(controller.detail(PRINCIPAL, API_KEY_ID).getHeaders().getETag())
                .isEqualTo("\"v0\"");
        assertThat(controller.update(
                PRINCIPAL,
                API_KEY_ID,
                "\"v0\"",
                new CurrentUserApiKeyController.UpdateRequest(
                        Status.ENABLED,
                        EXPIRES_AT)).getHeaders().getETag())
                .isEqualTo("\"v0\"");
        assertThat(controller.replaceModels(
                PRINCIPAL,
                API_KEY_ID,
                "\"v0\"",
                new CurrentUserApiKeyController.ReplaceModelsRequest(
                        List.of("ARRtIXbCEAA"))).getHeaders().getETag())
                .isEqualTo("\"v0\"");
        assertThat(controller.list(PRINCIPAL, null, 20).getHeaders().getETag())
                .isNull();
        assertThat(controller.delete(PRINCIPAL, API_KEY_ID, "\"v0\"")
                .getHeaders().getETag()).isNull();
    }

    private static UserApiKeyService service() {
        Summary summary = new Summary(
                API_KEY_ID.encoded(),
                "sk-…TEST",
                Status.ENABLED,
                EXPIRES_AT,
                false,
                null,
                CREATED_AT,
                CREATED_AT,
                0L);
        Detail detail = new Detail(
                summary,
                List.of(new ModelGrant(
                        "ARRtIXbCEAA",
                        "gpt-5.6-sol",
                        "openai",
                        true)));
        UserApiKeyService service = mock(UserApiKeyService.class);
        when(service.create(anyLong(), any())).thenReturn(
                new Created(detail, "sk-" + "A".repeat(86)));
        when(service.list(anyLong(), isNull(), anyInt())).thenReturn(
                new Page(List.of(summary), null));
        when(service.detail(anyLong(), any())).thenReturn(detail);
        when(service.update(anyLong(), any(), anyLong(), any())).thenReturn(detail);
        when(service.replaceModels(anyLong(), any(), anyLong(), any())).thenReturn(detail);
        return service;
    }
}
