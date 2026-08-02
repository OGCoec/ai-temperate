package com.example.temperate.service.user.aiconversation.model;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.util.Arrays;
import java.util.List;

/**
 * 定义普通用户可选择的五档推理强度，并负责把短整数协议映射为 CLIProxyAPI 参数。
 *
 * <p>浏览器只发送稳定的 1 至 5，业务层只传递枚举；上游字符串集中保存在这里，
 * 防止不同调用链把 Extra High 或 Ultra 错写成 CLIProxyAPI 不识别的值。</p>
 */
public enum AiConversationReasoningEffort {

    LOW((short) 1, "low"),
    MEDIUM((short) 2, "medium"),
    HIGH((short) 3, "high"),
    EXTRA_HIGH((short) 4, "xhigh"),
    ULTRA((short) 5, "max");

    private static final List<Short> SUPPORTED_LEVELS =
            Arrays.stream(values())
                    .map(AiConversationReasoningEffort::level)
                    .toList();

    private final short level;
    private final String upstreamValue;

    AiConversationReasoningEffort(short level, String upstreamValue) {
        this.level = level;
        this.upstreamValue = upstreamValue;
    }

    public short level() {
        return level;
    }

    public String upstreamValue() {
        return upstreamValue;
    }

    /**
     * 把可选请求值转换为业务枚举；旧客户端未发送字段时固定回退到 Medium。
     *
     * @param level 前端发送的 1 至 5，允许为 {@code null}
     * @return 受控推理强度枚举
     */
    public static AiConversationReasoningEffort fromLevel(Short level) {
        if (level == null) {
            return MEDIUM;
        }
        for (AiConversationReasoningEffort effort : values()) {
            if (effort.level == level.shortValue()) {
                return effort;
            }
        }
        throw new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                "推理强度必须是 1 到 5 的整数。",
                false);
    }

    public static short defaultLevel() {
        return MEDIUM.level;
    }

    public static List<Short> supportedLevels() {
        return SUPPORTED_LEVELS;
    }
}
