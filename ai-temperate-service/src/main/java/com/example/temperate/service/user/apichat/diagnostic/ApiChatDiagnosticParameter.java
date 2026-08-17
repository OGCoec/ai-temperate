package com.example.temperate.service.user.apichat.diagnostic;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 该工具是来把 Chat 错误参数压缩为固定协议字段或有界索引路径，防止客户端自定义字段名形成敏感或高基数诊断标签。
 */
public final class ApiChatDiagnosticParameter {

    private static final Set<String> FIXED_PARAMETERS = Set.of(
            "body",
            "model",
            "stream",
            "messages",
            "tools",
            "max_completion_tokens",
            "max_tokens",
            "stream_options.include_usage",
            "reasoning_effort",
            "prompt_cache_key",
            "store",
            "service_tier",
            "temperature",
            "top_p",
            "presence_penalty",
            "frequency_penalty",
            "seed",
            "n",
            "parallel_tool_calls",
            "stop",
            "tool_choice",
            "messages[].content[].field");
    private static final Pattern MESSAGE_PARAMETER = Pattern.compile(
            "messages\\[(?:0|[1-9][0-9]{0,3})\\]"
                    + "(?:\\.content(?:\\[(?:0|[1-9][0-9]{0,3})\\]"
                    + "(?:\\.(?:type|text))?)?"
                    + "|\\.reasoning_content|\\.tool_calls|\\.tool_call_id)?");
    private static final Pattern TOOL_DESCRIPTION_PARAMETER = Pattern.compile(
            "tools\\[(?:0|[1-9][0-9]{0,2})\\]\\.function\\.description");

    private ApiChatDiagnosticParameter() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        if (FIXED_PARAMETERS.contains(value)
                || MESSAGE_PARAMETER.matcher(value).matches()
                || TOOL_DESCRIPTION_PARAMETER.matcher(value).matches()) {
            return value;
        }
        return "unsupported";
    }
}
