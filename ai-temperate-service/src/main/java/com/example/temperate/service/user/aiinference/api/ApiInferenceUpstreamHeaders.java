package com.example.temperate.service.user.aiinference.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;

/**
 * 该值对象是来复制上游允许公开的关联与限流响应头，禁止携带 Cookie、认证信息、内部路由或任意供应商头。
 */
public record ApiInferenceUpstreamHeaders(Map<String, List<String>> values) {

    private static final ApiInferenceUpstreamHeaders EMPTY =
            new ApiInferenceUpstreamHeaders(Map.of());

    public ApiInferenceUpstreamHeaders {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((name, entries) -> {
                String normalized = name == null
                        ? "" : name.toLowerCase(Locale.ROOT);
                if (allowed(normalized) && safeValues(entries)) {
                    copy.put(normalized, List.copyOf(entries));
                }
            });
        }
        values = Map.copyOf(copy);
    }

    public static ApiInferenceUpstreamHeaders from(HttpHeaders source) {
        Map<String, List<String>> safe = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((name, entries) -> {
                String normalized = name.toLowerCase(Locale.ROOT);
                if (allowed(normalized) && safeValues(entries)) {
                    safe.put(normalized, List.copyOf(entries));
                }
            });
        }
        return new ApiInferenceUpstreamHeaders(safe);
    }

    /**
     * 返回不携带任何上游响应头的共享不可变实例，供未经过 HTTP 上游的兼容路径使用。
     */
    public static ApiInferenceUpstreamHeaders empty() {
        return EMPTY;
    }

    public HttpHeaders toHttpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        values.forEach(headers::put);
        return headers;
    }

    private static boolean allowed(String name) {
        return "x-request-id".equals(name)
                || "retry-after".equals(name)
                || name.startsWith("openai-")
                || name.startsWith("x-ratelimit-");
    }

    private static boolean safeValues(List<String> values) {
        if (values == null || values.size() > 16) {
            return false;
        }
        int total = 0;
        for (String value : values) {
            if (value == null || value.length() > 2_048
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                return false;
            }
            total += value.length();
        }
        return total <= 8_192;
    }
}
