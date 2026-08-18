package com.example.temperate.service.user.apiresponse.diagnostic;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 该工具是来把 Responses 错误参数限制为固定字段或有界数组路径，防止客户端自定义字段形成敏感或高基数日志。
 */
public final class ApiResponseDiagnosticParameter {

    private static final Set<String> FIXED_PARAMETERS = Set.of(
            "body",
            "model",
            "input",
            "instructions",
            "stream",
            "store",
            "max_output_tokens",
            "reasoning",
            "reasoning.effort",
            "reasoning.summary",
            "tools",
            "tool_choice",
            "tool_choice.type",
            "tool_choice.name",
            "parallel_tool_calls",
            "include",
            "prompt_cache_key",
            "service_tier",
            "text",
            "text.format",
            "text.format.type",
            "text.verbosity",
            "temperature",
            "top_p");
    private static final String INDEX = "(?:0|[1-9][0-9]{0,3})";
    private static final Pattern INPUT_PARAMETER = Pattern.compile(
            "input\\[" + INDEX + "]"
                    + "(?:\\.(?:type|role|content|id|status|call_id|name|arguments|output|"
                    + "encrypted_content|summary)"
                    + "(?:\\[" + INDEX + "])?"
                    + "(?:\\.(?:type|text))?)?");
    private static final Pattern TOOL_PARAMETER = Pattern.compile(
            "tools\\[" + INDEX + "]"
                    + "(?:\\.(?:type|name|description|parameters|strict))?");

    private ApiResponseDiagnosticParameter() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        if (FIXED_PARAMETERS.contains(value)
                || INPUT_PARAMETER.matcher(value).matches()
                || TOOL_PARAMETER.matcher(value).matches()) {
            return value;
        }
        return "unsupported";
    }
}
