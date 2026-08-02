package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassifier;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureDiagnostic;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureDiagnosticService;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 在独立 Spring Bean 中执行流失败分类，使 AOP 能拦截异步终止点发起的跨 Bean 调用。
 */
@Service
public final class AiConversationStreamFailureDiagnosticServiceImpl
        implements AiConversationStreamFailureDiagnosticService {

    private final AiConversationStreamFailureClassifier failureClassifier;

    public AiConversationStreamFailureDiagnosticServiceImpl(
            AiConversationStreamFailureClassifier failureClassifier) {
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
    }

    @Override
    @AiConversationStreamFailureDiagnostic
    public AiConversationStreamFailureClassification diagnose(
            AiConversationStreamFailureContext context,
            Throwable failure) {
        Objects.requireNonNull(context);
        return failureClassifier.classify(failure);
    }
}
