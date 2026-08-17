package com.example.temperate.web.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.model.ApiKeyModelDiscoveryException;
import com.example.temperate.service.user.apikey.model.ApiKeyModelDiscoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 该测试是来约束公开 Models API 的 OpenAI 响应形状、无缓存响应头和目录不可用错误边界。
 */
final class ApiKeyModelDiscoveryControllerTest {

    @Test
    void returnsOpenAiCompatibleAuthorizedModelListWithoutCaching() {
        LocalDate createdAt = LocalDate.of(2026, 8, 15);
        ApiKeyModelDiscoveryService service = ignored -> List.of(
                new ApiKeyModelDiscoveryService.AuthorizedModel(
                        "gpt-test", createdAt.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()));
        ApiKeyModelDiscoveryController controller = new ApiKeyModelDiscoveryController(service);

        ResponseEntity<ApiKeyModelDiscoveryResponse> response = controller.list(principal());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getCacheControl())
                .contains("no-store", "private", "no-transform");
        assertThat(response.getHeaders().getFirst("CDN-Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody())
                .extracting(ApiKeyModelDiscoveryResponse::object)
                .isEqualTo("list");
        assertThat(response.getBody().data()).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo("gpt-test");
            assertThat(model.object()).isEqualTo("model");
            assertThat(model.created()).isEqualTo(
                    createdAt.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond());
            assertThat(model.ownedBy()).isEqualTo("ai-temperate");
        });
        JsonNode json = new ObjectMapper().valueToTree(response.getBody());
        assertThat(json.path("object").asText()).isEqualTo("list");
        assertThat(json.path("data").get(0).path("owned_by").asText())
                .isEqualTo("ai-temperate");
        assertThat(json.path("data").get(0).has("vendor")).isFalse();
        assertThat(json.path("data").get(0).has("idInternal")).isFalse();
    }

    @Test
    void convertsModelCatalogFailureToOpenAiServiceUnavailableResponse() {
        ApiKeyModelDiscoveryExceptionHandler handler =
                new ApiKeyModelDiscoveryExceptionHandler();

        ResponseEntity<OpenAiErrorResponseWriter.Envelope> response = handler.unavailable(
                ApiKeyModelDiscoveryException.unavailable(new IllegalStateException("Redis")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getCacheControl())
                .contains("no-store", "private", "no-transform");
        assertThat(response.getBody().error())
                .extracting(OpenAiErrorResponseWriter.Error::code)
                .isEqualTo("model_catalog_unavailable");
    }

    private static ApiKeyPrincipal principal() {
        return new ApiKeyPrincipal(1L, 2L, new byte[32], "A".repeat(43), Set.of(7L));
    }
}
