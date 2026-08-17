package com.example.temperate.web.apikey;

/**
 * 该路径表是来集中限定 Bearer API Key 可进入的公开 v1 接口，避免不同过滤器对同一路径产生不一致的安全边界。
 */
public final class ApiKeyV1Paths {

    public static final String CHAT_COMPLETIONS = "/v1/chat/completions";
    public static final String MODELS = "/v1/models";

    private ApiKeyV1Paths() {
    }

    public static boolean isApiKeyEndpoint(String method, String requestUri) {
        return ("POST".equals(method) && CHAT_COMPLETIONS.equals(requestUri))
                || ("GET".equals(method) && MODELS.equals(requestUri));
    }
}
