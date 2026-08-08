package com.example.temperate.service.user.aiconversation.model;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 将数据库模型厂商标识严格映射为项目内部协议供应商，并集中约束供应商支持的推理等级。
 *
 * <p>该枚举只接受管理员模型记录中的明确 vendor，不根据模型名、图标或端点猜测供应商，
 * 防止模型重命名后请求被静默发送到错误协议。</p>
 */
public enum AiModelProvider {
    OPENAI("openai", List.of((short) 1, (short) 2, (short) 3,
            (short) 4, (short) 5)),
    XAI("xai", List.of((short) 1, (short) 2, (short) 3)),
    ANTHROPIC("anthropic", List.of((short) 1, (short) 2, (short) 3,
            (short) 4, (short) 5)),
    GOOGLE("google", List.of((short) 1, (short) 2, (short) 3, (short) 4));

    private final String vendor;
    private final List<Short> supportedReasoningLevels;

    AiModelProvider(String vendor, List<Short> supportedReasoningLevels) {
        this.vendor = vendor;
        this.supportedReasoningLevels = List.copyOf(supportedReasoningLevels);
    }

    public String vendor() {
        return vendor;
    }

    public List<Short> supportedReasoningLevels() {
        return supportedReasoningLevels;
    }

    public void validateReasoningEffort(AiConversationReasoningEffort effort) {
        Objects.requireNonNull(effort);
        if (!supportedReasoningLevels.contains(effort.level())) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "所选模型供应商不支持该推理强度",
                    false);
        }
    }

    public static AiModelProvider fromVendor(String vendor) {
        if (vendor != null) {
            String normalized = vendor.trim().toLowerCase(Locale.ROOT);
            for (AiModelProvider provider : values()) {
                if (provider.vendor.equals(normalized)) {
                    return provider;
                }
            }
        }
        throw new AiConversationException(
                AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                "所选模型供应商当前不受支持",
                false);
    }
}
