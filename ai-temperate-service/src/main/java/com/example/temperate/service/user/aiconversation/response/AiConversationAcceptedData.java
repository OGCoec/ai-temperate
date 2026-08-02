package com.example.temperate.service.user.aiconversation.response;

/**
 * 表示预扣事务提交后可通过 accepted 事件公开的公共资源标识。
 */
public record AiConversationAcceptedData(
        String conversationPublicId,
        String usagePublicId,
        String modelPublicId,
        boolean newConversation,
        String generationPublicId) {

    public AiConversationAcceptedData(
            String conversationPublicId,
            String usagePublicId,
            String modelPublicId,
            boolean newConversation) {
        this(
                conversationPublicId,
                usagePublicId,
                modelPublicId,
                newConversation,
                null);
    }
}
