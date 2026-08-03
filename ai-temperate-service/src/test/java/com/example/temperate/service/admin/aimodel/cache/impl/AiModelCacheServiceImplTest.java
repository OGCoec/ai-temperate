package com.example.temperate.service.admin.aimodel.cache.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.mapper.ai.AiModelCapabilityMapper;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.model.ai.entity.AiModelCapability;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.admin.aimodel.config.AiModelCacheProperties;
import com.example.temperate.service.admin.aimodel.security.AiModelCacheProtector;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 验证启用模型快照只使用固定聚合 Key、空集合防穿透，以及写入内容不包含模型明文。
 */
@ExtendWith(MockitoExtension.class)
final class AiModelCacheServiceImplTest {

    private static final String TEST_KEY_BASE64 =
            Base64.getEncoder().encodeToString(new byte[32]);
    private static final String CACHE_KEY = "ait:test:ai:model:v5:enabled";

    @Mock
    private AiModelMapper modelMapper;
    @Mock
    private AiModelCapabilityMapper capabilityMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AiModelCacheServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new AiModelCacheServiceImpl(
                modelMapper,
                capabilityMapper,
                redisTemplate,
                new RedisKeyFactory("test"),
                new AiModelCacheProtector(TEST_KEY_BASE64, objectMapper),
                new AiModelCacheProperties(
                        TEST_KEY_BASE64,
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(15),
                        500),
                objectMapper,
                new SimpleMeterRegistry());
    }

    @Test
    void writesEncryptedEmptySnapshotWhenNoModelIsEnabled() {
        when(modelMapper.findEnabled(501)).thenReturn(List.of());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.refreshEnabledSnapshot();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).unlink(CACHE_KEY);
        verify(valueOperations).set(eq(CACHE_KEY), valueCaptor.capture(), any(Duration.class));
        AiModelCacheSnapshot snapshot =
                new AiModelCacheProtector(TEST_KEY_BASE64, new ObjectMapper())
                        .unprotect(CACHE_KEY, valueCaptor.getValue());
        assertThat(snapshot.models()).isEmpty();
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void returnsCachedSnapshotWithoutQueryingDatabase() {
        AiModelCacheSnapshot cached = new AiModelCacheSnapshot(
                AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION,
                List.of());
        String envelope = new AiModelCacheProtector(TEST_KEY_BASE64, new ObjectMapper())
                .protect(CACHE_KEY, cached)
                .envelope();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(envelope);

        AiModelCacheSnapshot result = service.getOrLoadEnabledSnapshot();

        assertThat(result.models()).isEmpty();
        verifyNoInteractions(modelMapper, capabilityMapper);
    }

    @Test
    void rejectsPreviousMediaCapabilitySnapshotThenUnlinksLoadsDatabaseAndWritesV6() {
        ObjectMapper objectMapper = new ObjectMapper();
        AiModelCacheSnapshot v5 = new AiModelCacheSnapshot(5, List.of());
        String envelope = new AiModelCacheProtector(TEST_KEY_BASE64, objectMapper)
                .protect(CACHE_KEY, v5)
                .envelope();
        AiModel model = model();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(envelope);
        when(modelMapper.findEnabled(501)).thenReturn(List.of(model));
        when(capabilityMapper.findByAiModelIds(List.of(model.getId())))
                .thenReturn(List.of());

        AiModelCacheSnapshot result = service.getOrLoadEnabledSnapshot();

        assertThat(result.schemaVersion()).isEqualTo(6);
        assertThat(result.models()).hasSize(1);
        verify(redisTemplate, times(2)).unlink(CACHE_KEY);
        verify(valueOperations).set(eq(CACHE_KEY), any(String.class), any(Duration.class));
    }

    @Test
    void returnsDatabaseSnapshotWhenBestEffortCacheWriteFails() {
        AiModel model = model();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(modelMapper.findEnabled(501)).thenReturn(List.of(model));
        when(capabilityMapper.findByAiModelIds(List.of(model.getId())))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("redis unavailable"))
                .when(valueOperations)
                .set(eq(CACHE_KEY), any(String.class), any(Duration.class));

        AiModelCacheSnapshot result = service.getOrLoadEnabledSnapshot();

        assertThat(result.models()).singleElement()
                .satisfies(entry -> assertThat(entry.modelName()).isEqualTo("gpt-5.5"));
        verify(modelMapper).findEnabled(501);
        verify(capabilityMapper).findByAiModelIds(List.of(model.getId()));
    }

    @Test
    void writesOneEncryptedAggregateSnapshotForEnabledModels() {
        AiModel model = model();
        AiModelCapability capability = new AiModelCapability();
        capability.setAiModelId(model.getId());
        capability.setCapabilityCode(AiModelCapabilityCode.RESPONSES);
        when(modelMapper.findEnabled(501)).thenReturn(List.of(model));
        when(capabilityMapper.findByAiModelIds(List.of(model.getId())))
                .thenReturn(List.of(capability));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.refreshEnabledSnapshot();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).unlink(CACHE_KEY);
        verify(valueOperations).set(eq(CACHE_KEY), valueCaptor.capture(), any(Duration.class));
        assertThat(valueCaptor.getValue())
                .startsWith("v1.")
                .doesNotContain("gpt-5.5")
                .doesNotContain("openai")
                .doesNotContain("RESPONSES");
        AiModelCacheSnapshot snapshot =
                new AiModelCacheProtector(TEST_KEY_BASE64, new ObjectMapper())
                        .unprotect(CACHE_KEY, valueCaptor.getValue());
        assertThat(snapshot.models().get(0).cachedInputRatio())
                .isEqualByComparingTo("0.50000000");
        assertThat(snapshot.models().get(0).contextWindowTokens()).isEqualTo(256000L);
        assertThat(snapshot.models().get(0).maxOutputTokens()).isEqualTo(32000L);
        assertThat(new ObjectMapper().valueToTree(snapshot).toString())
                .doesNotContain("contextWindowK")
                .doesNotContain("maxOutputK");
    }

    @Test
    void refusesToCacheEnabledModelWithoutTokenLimits() {
        AiModel unconfigured = model();
        unconfigured.setContextWindowTokens(null);
        unconfigured.setMaxOutputTokens(null);
        when(modelMapper.findEnabled(501)).thenReturn(List.of(unconfigured));
        when(capabilityMapper.findByAiModelIds(List.of(unconfigured.getId())))
                .thenReturn(List.of());

        assertThatThrownBy(service::refreshEnabledSnapshot)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token limits");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void rejectsAndUnlinksAnInvalidEncryptedSnapshot() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn("plaintext-is-not-allowed");

        assertThat(service.findEnabledSnapshot()).isEmpty();

        verify(redisTemplate).unlink(CACHE_KEY);
    }

    private static AiModel model() {
        AiModel model = new AiModel();
        model.setId(123L);
        model.setModelName("gpt-5.5");
        model.setDescription("test model");
        model.setTagsJson("[\"chat\"]");
        model.setVendor("openai");
        model.setInputRatio(BigDecimal.ONE);
        model.setCachedInputRatio(new BigDecimal("0.50000000"));
        model.setOutputRatio(BigDecimal.TWO);
        model.setContextWindowTokens(256000L);
        model.setMaxOutputTokens(32000L);
        model.setEnabled(true);
        return model;
    }
}
