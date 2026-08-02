package com.example.temperate.service.user.aiconversation.diagnostic;

/**
 * 负责把不可信的模型上游异常归一化为安全、稳定且不依赖异常文案的流终止分类。
 */
public interface AiConversationStreamFailureClassifier {

    AiConversationStreamFailureClassification classify(Throwable failure);
}
