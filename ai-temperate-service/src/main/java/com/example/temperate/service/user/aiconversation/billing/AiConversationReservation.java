package com.example.temperate.service.user.aiconversation.billing;

/**
 * 表示预扣事务提交后的不可变调用凭证，包括会话、usage、倍率快照和预扣额度。
 */
public record AiConversationReservation(
        byte[] conversationId,
        byte[] usageId,
        Long completedMessageId,
        int billingStatus,
        long reservedQuotaMinor,
        AiConversationReservationMetering metering,
        boolean newConversation,
        boolean replay) {

    public AiConversationReservation {
        conversationId = conversationId.clone();
        usageId = usageId.clone();
        metering = java.util.Objects.requireNonNull(metering);
    }

    public AiConversationReservation(
            byte[] conversationId,
            byte[] usageId,
            Long completedMessageId,
            int billingStatus,
            long reservedQuotaMinor,
            long estimatedPromptTokens,
            long maxOutputTokens,
            java.math.BigDecimal inputRatio,
            java.math.BigDecimal cachedInputRatio,
            java.math.BigDecimal outputRatio,
            boolean newConversation,
            boolean replay) {
        this(conversationId, usageId, completedMessageId, billingStatus,
                reservedQuotaMinor,
                new TokenReservationMetering(
                        estimatedPromptTokens,
                        maxOutputTokens,
                        inputRatio,
                        cachedInputRatio,
                        outputRatio),
                newConversation,
                replay);
    }

    public long estimatedPromptTokens() {
        return tokenMetering().estimatedPromptTokens();
    }

    public long maxOutputTokens() {
        return tokenMetering().maxOutputTokens();
    }

    public java.math.BigDecimal inputRatio() {
        return tokenMetering().inputRatio();
    }

    public java.math.BigDecimal cachedInputRatio() {
        return tokenMetering().cachedInputRatio();
    }

    public java.math.BigDecimal outputRatio() {
        return tokenMetering().outputRatio();
    }

    private TokenReservationMetering tokenMetering() {
        if (metering instanceof TokenReservationMetering token) {
            return token;
        }
        throw new IllegalStateException(
                "Provider-cost reservation does not contain Token snapshots.");
    }
}
