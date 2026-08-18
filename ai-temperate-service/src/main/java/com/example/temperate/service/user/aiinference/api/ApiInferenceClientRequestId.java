package com.example.temperate.service.user.aiinference.api;

import com.example.temperate.service.user.apichat.ApiChatException;

/**
 * 该工具是来校验公开推理接口的 X-Client-Request-Id，防止控制字符、超长值或非 ASCII 内容进入上游与日志边界。
 */
public final class ApiInferenceClientRequestId {

    public static final String HEADER_NAME = "X-Client-Request-Id";

    private ApiInferenceClientRequestId() {
    }

    public static String validate(String value) {
        if (value == null) {
            return null;
        }
        if (value.isEmpty() || value.length() > 512) {
            throw ApiChatException.invalid(
                    "X-Client-Request-Id must contain 1 to 512 printable ASCII characters.",
                    HEADER_NAME);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7E) {
                throw ApiChatException.invalid(
                        "X-Client-Request-Id must contain 1 to 512 printable ASCII characters.",
                        HEADER_NAME);
            }
        }
        return value;
    }
}
