package com.example.temperate.service.admin.aimodel.discovery.client.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.temperate.service.admin.aimodel.discovery.config.CliProxyModelDiscoveryConfiguration;
import com.example.temperate.service.admin.aimodel.discovery.config.CliProxyModelDiscoveryProperties;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryErrorCode;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 验证 CLIProxyAPI 客户端始终使用固定 GET 路径、Bearer 认证且不发送请求体。
 */
final class RestClientCliProxyModelClientTest {

    private static final String API_KEY = "test-cli-proxy-key";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sendsAuthenticatedGetWithoutBodyAndReturnsJson() {
        RestClient.Builder builder = RestClient.builder();
        new CliProxyModelDiscoveryConfiguration()
                .customizeCliProxyModelRestClientBuilder(
                        builder,
                        properties(true, API_KEY));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        RestClientCliProxyModelClient modelClient =
                modelClient(client);

        server.expect(requestTo("http://127.0.0.1:8317/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().string(""))
                .andRespond(withSuccess(
                        """
                        {"object":"list","data":[{"id":"gpt-5.4","owned_by":"openai"}]}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(modelClient.fetchModels().path("object").asText()).isEqualTo("list");
        server.verify();
    }

    @Test
    void mapsAuthenticationAndTimeoutFailuresWithoutExposingResponseBody() {
        RestClient.Builder authBuilder = RestClient.builder();
        new CliProxyModelDiscoveryConfiguration()
                .customizeCliProxyModelRestClientBuilder(
                        authBuilder,
                        properties(true, API_KEY));
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        RestClient authClient = authBuilder.build();
        authServer.expect(requestTo("http://127.0.0.1:8317/v1/models"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("sensitive-upstream-body"));

        assertThatThrownBy(() -> modelClient(authClient).fetchModels())
                .isInstanceOfSatisfying(
                        CliProxyModelDiscoveryException.class,
                        exception -> {
                            assertThat(exception.code())
                                    .isEqualTo(CliProxyModelDiscoveryErrorCode.CLI_PROXY_AUTH_FAILED);
                            assertThat(exception.getMessage())
                                    .doesNotContain("sensitive-upstream-body")
                                    .doesNotContain(API_KEY);
                        });

        RestClient.Builder timeoutBuilder = RestClient.builder();
        new CliProxyModelDiscoveryConfiguration()
                .customizeCliProxyModelRestClientBuilder(
                        timeoutBuilder,
                        properties(true, API_KEY));
        MockRestServiceServer timeoutServer =
                MockRestServiceServer.bindTo(timeoutBuilder).build();
        RestClient timeoutClient = timeoutBuilder.build();
        timeoutServer.expect(requestTo("http://127.0.0.1:8317/v1/models"))
                .andRespond(withException(new SocketTimeoutException("timed out")));

        assertThatThrownBy(() -> modelClient(timeoutClient).fetchModels())
                .isInstanceOfSatisfying(
                        CliProxyModelDiscoveryException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(CliProxyModelDiscoveryErrorCode.CLI_PROXY_TIMEOUT));
    }

    @Test
    void mapsUnavailableServerFailureAndUnreadableJson() {
        assertFailure(
                withException(new ConnectException("connection refused")),
                CliProxyModelDiscoveryErrorCode.CLI_PROXY_UNAVAILABLE);
        assertFailure(
                withException(new VendorConnectTimeoutException()),
                CliProxyModelDiscoveryErrorCode.CLI_PROXY_TIMEOUT);
        assertFailure(
                withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("upstream detail"),
                CliProxyModelDiscoveryErrorCode.CLI_PROXY_REQUEST_FAILED);
        assertFailure(
                withSuccess("{invalid-json", MediaType.APPLICATION_JSON),
                CliProxyModelDiscoveryErrorCode.CLI_PROXY_RESPONSE_INVALID);
        assertFailure(
                withSuccess(
                        "{\"object\":\"list\",\"data\":[],\"padding\":\""
                                + "x".repeat(1024 * 1024)
                                + "\"}",
                        MediaType.APPLICATION_JSON),
                CliProxyModelDiscoveryErrorCode.CLI_PROXY_RESPONSE_INVALID);
    }

    private static void assertFailure(
            org.springframework.test.web.client.ResponseCreator responseCreator,
            CliProxyModelDiscoveryErrorCode expectedCode) {
        RestClient.Builder builder = RestClient.builder();
        new CliProxyModelDiscoveryConfiguration()
                .customizeCliProxyModelRestClientBuilder(
                        builder,
                        properties(true, API_KEY));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8317/v1/models"))
                .andRespond(responseCreator);

        assertThatThrownBy(() -> modelClient(builder.build()).fetchModels())
                .isInstanceOfSatisfying(
                        CliProxyModelDiscoveryException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private static CliProxyModelDiscoveryProperties properties(
            boolean enabled,
            String apiKey) {
        return new CliProxyModelDiscoveryProperties(
                enabled,
                URI.create("http://127.0.0.1:8317"),
                apiKey,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                500);
    }

    private static RestClientCliProxyModelClient modelClient(RestClient restClient) {
        return new RestClientCliProxyModelClient(restClient, OBJECT_MAPPER);
    }

    private static final class VendorConnectTimeoutException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
