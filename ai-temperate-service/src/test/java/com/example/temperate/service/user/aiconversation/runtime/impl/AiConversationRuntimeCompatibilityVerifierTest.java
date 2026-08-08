package com.example.temperate.service.user.aiconversation.runtime.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证图片运行时关键类型会在应用就绪前完成类加载检查，避免半编译实例接收请求。
 */
final class AiConversationRuntimeCompatibilityVerifierTest {

    @Test
    void completeRuntimeClasspathPassesVerification() {
        AiConversationRuntimeCompatibilityVerifier verifier =
                new AiConversationRuntimeCompatibilityVerifier(
                        Thread.currentThread().getContextClassLoader());

        assertThatCode(verifier::afterSingletonsInstantiated)
                .doesNotThrowAnyException();
    }

    @Test
    void missingImagePhasePreventsApplicationReadiness() {
        String missingClass = "com.example.temperate.service.user.aiconversation.image."
                + "AiConversationGeneratedImagePhase";
        ClassLoader rejectingLoader = new ClassLoader(
                Thread.currentThread().getContextClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (missingClass.equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        AiConversationRuntimeCompatibilityVerifier verifier =
                new AiConversationRuntimeCompatibilityVerifier(rejectingLoader);

        assertThatThrownBy(verifier::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI 图片运行时依赖不完整")
                .hasRootCauseInstanceOf(ClassNotFoundException.class);
    }
}
