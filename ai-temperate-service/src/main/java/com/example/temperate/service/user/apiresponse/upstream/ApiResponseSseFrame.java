package com.example.temperate.service.user.apiresponse.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUsage;

/**
 * 该帧是来携带已校验的 Responses 原生事件、客户端可见输出字节和权威终态事实，业务状态机不再重复解析 JSON。
 */
public record ApiResponseSseFrame(
        String eventName,
        String data,
        long outputUtf8Bytes,
        long sequenceNumber,
        TerminalKind terminalKind,
        ApiInferenceUsage usage,
        String finishReason) {

    /** 该枚举是来区分普通帧、权威 Responses 终态和仅供兼容吞掉的上游 Chat 结束标记。 */
    public enum TerminalKind {
        NONE,
        COMPLETED,
        INCOMPLETE,
        FAILED,
        ERROR,
        LEGACY_DONE
    }
}
