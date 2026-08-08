package com.example.temperate.service.user.aiconversation.video;

/**
 * 保存单个视频请求的供应商成本证据；缺失成本时仍保留请求 ID 以进入人工对账而不是猜测费用。
 */
public record AiConversationVideoMeteringEvidence(
        String requestId,
        Long costInUsdTicks) {

    public AiConversationVideoMeteringEvidence {
        if (requestId == null
                || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("xAI video request ID is invalid.");
        }
        if (costInUsdTicks != null && costInUsdTicks < 0L) {
            throw new IllegalArgumentException("xAI video cost ticks are invalid.");
        }
    }
}
