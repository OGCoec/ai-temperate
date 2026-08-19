package com.example.temperate.web.user.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageModels.Page;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageModels.Period;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageModels.Summary;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该契约测试是来保证当前用户 API Key 调用记录接口保持独立中文 OpenAPI 分组，并只向 Service 传递会话用户和查询边界。
 */
final class CurrentUserApiKeyUsageControllerContractTest {

    private static final byte[] API_KEY_INTERNAL_ID = {
            0x01, (byte) 0x9b, 0x12, 0x34, 0x56, 0x78, 0x01, 0x02,
            0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a
    };

    @Test
    void delegatesToUsageServiceAndReturnsNoEtag() throws Exception {
        ApiKeyUsageQueryService service = mock(ApiKeyUsageQueryService.class);
        Page page = new Page(
                new Period("2026-08-18T15:00:00Z", "2026-08-18T16:00:00Z"),
                new Summary("0", "0", "0", "0", "0", "0", "0", "0"),
                List.of(),
                null);
        when(service.query(7L, API_KEY_INTERNAL_ID, null, null, null, 20)).thenReturn(page);
        CurrentUserApiKeyUsageController controller =
                new CurrentUserApiKeyUsageController(service);

        var response = controller.usage(
                new SessionPrincipal(7L, "ARCRqojCEAA", "Usage Test User"),
                new ApiKeyPublicId("01KC938NKR041061050R3GG28A", API_KEY_INTERNAL_ID),
                null,
                null,
                null,
                20);

        assertThat(response.getHeaders().getETag()).isNull();
        assertThat(response.getBody().summary().requestCount()).isEqualTo("0");
        verify(service).query(7L, API_KEY_INTERNAL_ID, null, null, null, 20);

        Tag tag = CurrentUserApiKeyUsageController.class.getAnnotation(Tag.class);
        Method method = CurrentUserApiKeyUsageController.class.getMethod(
                "usage",
                SessionPrincipal.class,
                ApiKeyPublicId.class,
                OffsetDateTime.class,
                OffsetDateTime.class,
                String.class,
                int.class);
        assertThat(tag.name()).contains("调用记录");
        assertThat(method.getAnnotation(Operation.class).summary()).contains("调用");
        assertThat(Arrays.stream(ApiKeyUsageResponse.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("apiKeyId", "apiKey", "keyDigest", "usageId");
    }
}
