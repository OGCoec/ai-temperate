package com.example.temperate.common.redis.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证会话上下文重建临时标识只能使用固定长度的小写随机十六进制值。
 */
final class ConversationRedisBuildIdTest {

    @Test
    void acceptsCanonicalBuildId() {
        ConversationRedisBuildId id = new ConversationRedisBuildId(
                "0123456789abcdef0123456789abcdef");

        assertThat(id.value()).hasSize(32);
    }

    @Test
    void rejectsNonCanonicalBuildId() {
        assertThatThrownBy(() -> new ConversationRedisBuildId("not-valid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
