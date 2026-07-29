package com.example.temperate.service.admin.aimodel.discovery.client.impl;

import com.example.temperate.service.admin.aimodel.discovery.client.CliProxyModelClient;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryErrorCode;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 使用同步 RestClient 调用 CLIProxyAPI 固定模型列表端点，并把传输失败收敛为安全错误码。
 *
 * <p>上游异常不作为 cause 向外传播，避免错误日志意外包含完整响应体、内部地址或认证信息。</p>
 */
@Component
public final class RestClientCliProxyModelClient implements CliProxyModelClient {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestClientCliProxyModelClient(
            @Qualifier("cliProxyModelRestClient") RestClient restClient,
            ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public JsonNode fetchModels() {
        try {
            JsonNode response = restClient.get()
                    .uri("/v1/models")
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, upstreamResponse) -> {
                        int status = upstreamResponse.getStatusCode().value();
                        if (status == HttpStatus.UNAUTHORIZED.value()
                                || status == HttpStatus.FORBIDDEN.value()) {
                            throw failure(
                                    CliProxyModelDiscoveryErrorCode
                                            .CLI_PROXY_AUTH_FAILED,
                                    "CLIProxyAPI rejected backend authentication.");
                        }
                        if (!upstreamResponse.getStatusCode().is2xxSuccessful()) {
                            throw failure(
                                    CliProxyModelDiscoveryErrorCode
                                            .CLI_PROXY_REQUEST_FAILED,
                                    "CLIProxyAPI returned a non-success status.");
                        }
                        // 在 Jackson 建树前硬限制正文，防止异常上游用巨大无关字段绕过模型数量上限。
                        byte[] body = upstreamResponse.getBody()
                                .readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
                            throw failure(
                                    CliProxyModelDiscoveryErrorCode
                                            .CLI_PROXY_RESPONSE_INVALID,
                                    "CLIProxyAPI returned an invalid response size.");
                        }
                        try {
                            return objectMapper.readTree(body);
                        } catch (IOException exception) {
                            throw failure(
                                    CliProxyModelDiscoveryErrorCode
                                            .CLI_PROXY_RESPONSE_INVALID,
                                    "CLIProxyAPI returned unreadable JSON.");
                        }
                    });
            if (response == null) {
                throw failure(
                        CliProxyModelDiscoveryErrorCode.CLI_PROXY_RESPONSE_INVALID,
                        "CLIProxyAPI returned an empty response.");
            }
            return response;
        } catch (CliProxyModelDiscoveryException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) {
                throw failure(
                        CliProxyModelDiscoveryErrorCode.CLI_PROXY_TIMEOUT,
                        "CLIProxyAPI request timed out.");
            }
            throw failure(
                    CliProxyModelDiscoveryErrorCode.CLI_PROXY_UNAVAILABLE,
                    "CLIProxyAPI is unavailable.");
        } catch (RestClientException exception) {
            throw failure(
                    CliProxyModelDiscoveryErrorCode.CLI_PROXY_RESPONSE_INVALID,
                    "CLIProxyAPI returned an unreadable response.");
        }
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    // 不绑定 Apache、Jetty 等具体客户端依赖，但仍要把它们的连接超时稳定映射为 504。
                    || current.getClass().getSimpleName().endsWith("TimeoutException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static CliProxyModelDiscoveryException failure(
            CliProxyModelDiscoveryErrorCode code,
            String message) {
        return new CliProxyModelDiscoveryException(code, message);
    }
}
