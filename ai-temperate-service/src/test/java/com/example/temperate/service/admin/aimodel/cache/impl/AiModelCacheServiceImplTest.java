package com.example.temperate.service.admin.aimodel.cache.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
 * 验证启用模型快照只使用固定聚合 Key、无模型时 UNLINK，以及写入内容不包含模型明文。
 */
@ExtendWith(MockitoExtension.class)
final class AiModelCacheServiceImplTest {

    private static final String TEST_KEY_BASE64 =
            Base64.getEncoder().encodeToString(new byte[32]);
    private static final String CACHE_KEY = "ait:test:ai:model:v3:enabled";

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
    void unlinksSnapshotWhenNoModelIsEnabled() {
        when(modelMapper.findEnabled(501)).thenReturn(List.of());

        service.refreshEnabledSnapshot();

        verify(redisTemplate).unlink(CACHE_KEY);
        verify(redisTemplate, never()).opsForValue();
        verifyNoInteractions(capabilityMapper);
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
        model.setEnabled(true);
        return model;
    }
}
