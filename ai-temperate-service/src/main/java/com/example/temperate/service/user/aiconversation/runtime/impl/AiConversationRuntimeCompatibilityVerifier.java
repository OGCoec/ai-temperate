package com.example.temperate.service.user.aiconversation.runtime.impl;

import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * 在 Spring 完成单例初始化时加载图片链路关键类型，防止不完整的增量编译产物进入 Ready 状态。
 *
 * <p>校验只确认当前应用 ClassLoader 能够加载关键运行时类型，不执行模型请求、网络访问或外部存储操作。</p>
 */
@Component
public final class AiConversationRuntimeCompatibilityVerifier
        implements SmartInitializingSingleton {

    private static final List<String> REQUIRED_IMAGE_RUNTIME_TYPES = List.of(
            "com.example.temperate.service.user.aiconversation.image."
                    + "AiConversationGeneratedImagePhase",
            "com.example.temperate.service.user.aiconversation.image."
                    + "AiConversationGeneratedImage",
            "com.example.temperate.service.user.aiconversation.model.stream.impl."
                    + "OpenAiImagesGenerationEventMapper");

    private final ClassLoader classLoader;

    public AiConversationRuntimeCompatibilityVerifier() {
        this(Objects.requireNonNullElse(
                ClassUtils.getDefaultClassLoader(),
                AiConversationRuntimeCompatibilityVerifier.class.getClassLoader()));
    }

    AiConversationRuntimeCompatibilityVerifier(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader);
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String typeName : REQUIRED_IMAGE_RUNTIME_TYPES) {
            try {
                Class.forName(typeName, true, classLoader);
            } catch (ClassNotFoundException | LinkageError failure) {
                // 启动期缺类无法通过请求级降级恢复，必须阻止实例变为 Ready，避免再次产生已计费但无法持久化的图片。
                throw new IllegalStateException(
                        "AI 图片运行时依赖不完整: " + typeName,
                        failure);
            }
        }
    }
}
