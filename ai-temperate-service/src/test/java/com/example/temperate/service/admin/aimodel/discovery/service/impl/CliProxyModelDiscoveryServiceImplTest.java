package com.example.temperate.service.admin.aimodel.discovery.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.service.admin.aimodel.discovery.client.CliProxyModelClient;
import com.example.temperate.service.admin.aimodel.discovery.config.CliProxyModelDiscoveryProperties;
import com.example.temperate.service.admin.aimodel.discovery.dto.CliProxyModelMatchStatus;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryErrorCode;
import com.example.temperate.service.admin.aimodel.discovery.exception.CliProxyModelDiscoveryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证模型发现服务严格校验上游列表，并只通过一次批量 SQL 丰富本地配置。
 */
final class CliProxyModelDiscoveryServiceImplTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-07-29T15:30:00Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void deduplicatesSortsAndEnrichesUsingOneBatchLookup() throws Exception {
        CliProxyModelClient client = mock(CliProxyModelClient.class);
        AiModelMapper mapper = mock(AiModelMapper.class);
        when(client.fetchModels()).thenReturn(OBJECT_MAPPER.readTree(
                """
                {
                  "object": "list",
                  "data": [
                    {"id": " GPT-5.4 ", "owned_by": "openai", "created": 1773709206},
                    {"id": "gpt-5.4-codex", "owned_by": "openai"},
                    {"id": "gpt-5.4", "owned_by": "duplicate"}
                  ]
                }
                """));
        AiModel local = new AiModel();
        local.setId(10L);
        local.setModelName("gpt-5.4");
        local.setVendor("openai");
        local.setInputRatio(new BigDecimal("1.00000000"));
        local.setCachedInputRatio(new BigDecimal("0.10000000"));
        local.setOutputRatio(new BigDecimal("4.00000000"));
        local.setEnabled(true);
        when(mapper.findByNormalizedModelNames(List.of("gpt-5.4", "gpt-5.4-codex")))
                .thenReturn(List.of(local));

        CliProxyModelDiscoveryServiceImpl service = service(client, mapper, properties(true, 500));

        var result = service.discoverModels();

        assertThat(result.source()).isEqualTo("CLI_PROXY");
        assertThat(result.fetchedAt()).isEqualTo(FETCHED_AT);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.models()).extracting(model -> model.modelId())
                .containsExactly("GPT-5.4", "gpt-5.4-codex");
        assertThat(result.models().get(0).matchStatus())
                .isEqualTo(CliProxyModelMatchStatus.MATCHED);
        assertThat(result.models().get(0).localModelPublicId())
                .isEqualTo(new PublicIdCodec().encode(10L));
        assertThat(result.models().get(0).inputRatio())
                .isEqualByComparingTo("1.00000000");
        assertThat(result.models().get(0).cachedInputRatio())
                .isEqualByComparingTo("0.10000000");
        assertThat(result.models().get(1).matchStatus())
                .isEqualTo(CliProxyModelMatchStatus.UNREGISTERED);
        assertThat(result.models().get(1).localModelPublicId()).isNull();
        verify(mapper).findByNormalizedModelNames(List.of("gpt-5.4", "gpt-5.4-codex"));
    }

    @Test
    void rejectsDisabledFeatureAndInvalidOrOversizedPayloadsBeforeDatabaseAccess()
            throws Exception {
        CliProxyModelClient disabledClient = mock(CliProxyModelClient.class);
        AiModelMapper disabledMapper = mock(AiModelMapper.class);
        CliProxyModelDiscoveryServiceImpl disabled =
                service(disabledClient, disabledMapper, properties(false, 500));

        assertThatThrownBy(disabled::discoverModels)
                .isInstanceOfSatisfying(
                        CliProxyModelDiscoveryException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(CliProxyModelDiscoveryErrorCode
                                        .CLI_PROXY_MODEL_DISCOVERY_DISABLED));
        verify(disabledClient, never()).fetchModels();
        verify(disabledMapper, never()).findByNormalizedModelNames(anyList());

        CliProxyModelClient invalidClient = mock(CliProxyModelClient.class);
        AiModelMapper invalidMapper = mock(AiModelMapper.class);
        when(invalidClient.fetchModels()).thenReturn(OBJECT_MAPPER.readTree(
                """
                {"object":"list","data":[{"id":"one"},{"id":"two"}]}
                """));
        CliProxyModelDiscoveryServiceImpl oversized =
                service(invalidClient, invalidMapper, properties(true, 1));

        assertThatThrownBy(oversized::discoverModels)
                .isInstanceOfSatisfying(
                        CliProxyModelDiscoveryException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(CliProxyModelDiscoveryErrorCode
                                        .CLI_PROXY_RESPONSE_INVALID));
        verify(invalidMapper, never()).findByNormalizedModelNames(anyList());

        CliProxyModelClient unsafeIntegerClient = mock(CliProxyModelClient.class);
        AiModelMapper unsafeIntegerMapper = mock(AiModelMapper.class);
        when(unsafeIntegerClient.fetchModels()).thenReturn(OBJECT_MAPPER.readTree(
                """
                {"object":"list","data":[{"id":"gpt-5.4","created":9007199254740992}]}
                """));
        CliProxyModelDiscoveryServiceImpl unsafeInteger =
                service(unsafeIntegerClient, unsafeIntegerMapper, properties(true, 500));

        assertThatThrownBy(unsafeInteger::discoverModels)
                .isInstanceOfSatisfying(
                        CliProxyModelDiscoveryException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(CliProxyModelDiscoveryErrorCode
                                        .CLI_PROXY_RESPONSE_INVALID));
        verify(unsafeIntegerMapper, never()).findByNormalizedModelNames(anyList());
    }

    private static CliProxyModelDiscoveryServiceImpl service(
            CliProxyModelClient client,
            AiModelMapper mapper,
            CliProxyModelDiscoveryProperties properties) {
        return new CliProxyModelDiscoveryServiceImpl(
                client,
                mapper,
                new PublicIdCodec(),
                properties,
                Clock.fixed(FETCHED_AT, ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    private static CliProxyModelDiscoveryProperties properties(
            boolean enabled,
            int maxModels) {
        return new CliProxyModelDiscoveryProperties(
                enabled,
                URI.create("http://127.0.0.1:8317"),
                "test-cli-proxy-key",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                maxModels);
    }
}
