package com.example.temperate.web.admin.mailinspection.api;

import java.util.Objects;

/**
 * 包装 SSE 数据中的权威 Redis revision 与事件载荷，客户端以 revision 去重而不依赖到达次数。
 */
public record AdminMailInspectionSseEventResponse(
        long revision,
        Object data) {

    public AdminMailInspectionSseEventResponse {
        if (revision < 0) {
            throw new IllegalArgumentException(
                    "SSE revision must not be negative");
        }
        Objects.requireNonNull(data, "SSE data must not be null");
    }
}
