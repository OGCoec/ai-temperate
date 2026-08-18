package com.example.temperate.service.user.apiresponse.diagnostic;

import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame.TerminalKind;

/**
 * 该枚举是来把开放式 Responses 事件名压缩为固定类别，避免未来事件名成为日志和指标的高基数输入。
 */
public enum ApiResponseFrameClass {
    LIFECYCLE,
    OUTPUT_TEXT,
    REASONING,
    FUNCTION,
    OUTPUT_ITEM,
    TERMINAL,
    ERROR,
    UNKNOWN;

    public static ApiResponseFrameClass classify(
            String eventName,
            TerminalKind terminalKind) {
        if (terminalKind == TerminalKind.COMPLETED
                || terminalKind == TerminalKind.INCOMPLETE
                || terminalKind == TerminalKind.FAILED) {
            return TERMINAL;
        }
        if (terminalKind == TerminalKind.ERROR) {
            return ERROR;
        }
        if (eventName == null) {
            return UNKNOWN;
        }
        if (eventName.equals("response.created")
                || eventName.equals("response.in_progress")
                || eventName.equals("response.queued")) {
            return LIFECYCLE;
        }
        if (eventName.startsWith("response.output_text.")) {
            return OUTPUT_TEXT;
        }
        if (eventName.startsWith("response.reasoning")) {
            return REASONING;
        }
        if (eventName.startsWith("response.function_call_arguments.")) {
            return FUNCTION;
        }
        if (eventName.startsWith("response.output_item.")) {
            return OUTPUT_ITEM;
        }
        if (eventName.equals("error")) {
            return ERROR;
        }
        return UNKNOWN;
    }
}
