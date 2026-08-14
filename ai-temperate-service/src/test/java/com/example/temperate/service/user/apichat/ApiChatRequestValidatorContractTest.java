package com.example.temperate.service.user.apichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.user.apichat.impl.ApiChatRequestValidatorImpl;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束公开请求的 JSON 强类型、唯一 Token 上限、stream=true、模型授权与上下文窗口，错误必须在连接 8317 前产生。
 */
final class ApiChatRequestValidatorContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiChatRequestValidator validator = new ApiChatRequestValidatorImpl(
            cacheService(model()), new ApiKeyProperties(), objectMapper);
    private final ApiKeyPrincipal principal = new ApiKeyPrincipal(
            1L, 2L, new byte[32], "A".repeat(43), Set.of(7L));

    @Test
    void appliesClientAndModelTokenMinimumToValidationAndBillingInput() throws Exception {
        ApiChatRequest request = request("""
                {"model":"GPT-TEST","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"max_completion_tokens":200,"temperature":0.5,
                 "seed":3,"n":1}
                """);

        ValidatedApiChatRequest validated = validator.validate(principal, request);

        assertThat(validated.effectiveMaxOutputTokens()).isEqualTo(200);
        assertThat(validated.estimatedPromptTokens()).isPositive();
    }

    @Test
    void rejectsNumericStringsAndCompetingTokenLimits() throws Exception {
        ApiChatRequest numericString = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"max_tokens":"200"}
                """);
        ApiChatRequest competing = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"max_tokens":200,"max_completion_tokens":200}
                """);

        assertInvalid(numericString, "max_tokens");
        assertInvalid(competing, "max_completion_tokens");
    }

    @Test
    void rejectsFalseStreamMultimodalContentAndUnauthorizedModel() throws Exception {
        ApiChatRequest falseStream = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":false}
                """);
        ApiChatRequest multimodal = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":[]}],
                 "stream":true}
                """);
        ApiKeyPrincipal noGrant = new ApiKeyPrincipal(
                1L, 2L, new byte[32], "A".repeat(43), Set.of());

        assertThatThrownBy(() -> validator.validate(principal, falseStream))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.STREAM_REQUIRED);
        assertInvalid(multimodal, "messages");
        assertThatThrownBy(() -> validator.validate(noGrant, request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true}
                """)))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.MODEL_NOT_ALLOWED);
    }

    @Test
    void treatsMissingStreamAsStreamRequiredAndAllowsEmptyStreamOptions() throws Exception {
        ApiChatRequest missingStream = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}]}
                """);
        ApiChatRequest emptyOptions = request("""
                {"model":"gpt-test","messages":[{"role":"user","content":"hello"}],
                 "stream":true,"stream_options":{}}
                """);

        assertThatThrownBy(() -> validator.validate(principal, missingStream))
                .isInstanceOf(ApiChatException.class)
                .extracting(failure -> ((ApiChatException) failure).code())
                .isEqualTo(ApiChatErrorCode.STREAM_REQUIRED);
        assertThat(validator.validate(principal, emptyOptions).includeUsage()).isFalse();
    }

    private void assertInvalid(ApiChatRequest request, String parameter) {
        assertThatThrownBy(() -> validator.validate(principal, request))
                .isInstanceOf(ApiChatException.class)
                .satisfies(failure -> assertThat(((ApiChatException) failure).parameter())
                        .isEqualTo(parameter));
    }

    private ApiChatRequest request(String json) throws Exception {
        return objectMapper.readValue(json, ApiChatRequest.class);
    }

    private static AiModelCacheEntry model() {
        return new AiModelCacheEntry(
                7L, "gpt-test", "openai", "test", null, List.of(),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                4_096, 512, List.of(AiModelCapabilityCode.CHAT_COMPLETIONS));
    }

    private static AiModelCacheService cacheService(AiModelCacheEntry model) {
        AiModelCacheSnapshot snapshot = new AiModelCacheSnapshot(1, List.of(model));
        return new AiModelCacheService() {
            @Override
            public Optional<AiModelCacheSnapshot> findEnabledSnapshot() {
                return Optional.of(snapshot);
            }

            @Override
            public AiModelCacheSnapshot getOrLoadEnabledSnapshot() {
                return snapshot;
            }

            @Override
            public void refreshEnabledSnapshot() {
                throw new UnsupportedOperationException("read-only validation fixture");
            }
        };
    }
}
