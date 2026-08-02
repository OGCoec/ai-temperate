package com.example.temperate.common.redis.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 会话上下文与两类租约只能使用规范的 Hybrid 公共 ID 生成 Redis Key。
 */
final class AiConversationRedisKeyFactoryTest {

    private final RedisKeyFactory factory = new RedisKeyFactory("test");

    @Test
    void createsSeparatedContextAndLeaseKeys() {
        ConversationRedisId id =
                new ConversationRedisId("AAAAAAAAAAAAAAAAAAAAAA");
        ConversationRedisBuildId buildId = new ConversationRedisBuildId(
                "0123456789abcdef0123456789abcdef");

        assertThat(factory.aiConversationContextKey(id))
                .isEqualTo("ait:test:ai:conversation:v1:ctx:AAAAAAAAAAAAAAAAAAAAAA");
        assertThat(factory.aiConversationInflightKey(id))
                .isEqualTo("ait:test:ai:conversation:v1:inflight:AAAAAAAAAAAAAAAAAAAAAA");
        assertThat(factory.aiConversationCompactionKey(id))
                .isEqualTo("ait:test:ai:conversation:v1:compact:AAAAAAAAAAAAAAAAAAAAAA");
        assertThat(factory.aiConversationContextBuildKey(id, buildId))
                .isEqualTo("ait:test:ai:conversation:v1:ctx-build:"
                        + "AAAAAAAAAAAAAAAAAAAAAA_0123456789abcdef0123456789abcdef")
                .hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void rejectsNonCanonicalConversationIds() {
        assertThatThrownBy(() -> new ConversationRedisId("123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsGenerationSnapshotKeyFromDedicatedPublicIdType() {
        GenerationRedisId generationId =
                new GenerationRedisId("BBBBBBBBBBBBBBBBBBBBBB");

        assertThat(factory.aiConversationGenerationSnapshotKey(generationId))
                .isEqualTo("ait:test:ai:generation:v1:snapshot:BBBBBBBBBBBBBBBBBBBBBB")
                .hasSizeLessThanOrEqualTo(RedisKeyFactory.TARGET_MAX_BYTES);
        assertThatThrownBy(() -> new GenerationRedisId("123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsBoundedGenerationDiagnosticRateLimitKey() {
        GenerationRedisId generationId =
                new GenerationRedisId("BBBBBBBBBBBBBBBBBBBBBB");

        assertThat(factory.aiConversationGenerationBrowserDiagnosticKey(generationId))
                .isEqualTo("ait:test:ai:generation:v1:browser-diagnostic:"
                        + "BBBBBBBBBBBBBBBBBBBBBB")
                .hasSizeLessThanOrEqualTo(RedisKeyFactory.TARGET_MAX_BYTES);
    }

    @Test
    void concurrencyKeysNeverContainPlainUserId() {
        HmacIdentifier protectedUser = HmacIdentifier.fromProtectedValue(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertThat(factory.aiConversationGlobalConcurrencyKey())
                .isEqualTo("ait:test:ai:conversation:v1:concurrency-global");
        assertThat(factory.aiConversationUserConcurrencyKey(protectedUser))
                .doesNotContain("10001")
                .contains(protectedUser.value())
                .hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void directResponseControlKeysUseOnlyProtectedIdempotencyIdentifier() {
        HmacIdentifier protectedRequest = HmacIdentifier.fromProtectedValue(
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");

        assertThat(factory.aiConversationDirectResponseOwnerKey(protectedRequest))
                .isEqualTo("ait:test:ai:response:v1:owner:" + protectedRequest.value())
                .hasSizeLessThanOrEqualTo(RedisKeyFactory.TARGET_MAX_BYTES);
        assertThat(factory.aiConversationDirectResponseCancelKey(protectedRequest))
                .isEqualTo("ait:test:ai:response:v1:cancel:" + protectedRequest.value())
                .hasSizeLessThanOrEqualTo(RedisKeyFactory.TARGET_MAX_BYTES);
    }
}
