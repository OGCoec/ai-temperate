package com.example.temperate.service.user.apikey.model;

/**
 * 该异常是来把授权模型目录的缓存或数据库读取故障收敛为公开 API 的受控不可用状态，不暴露内部基础设施细节。
 */
public final class ApiKeyModelDiscoveryException extends RuntimeException {

    private static final String UNAVAILABLE_MESSAGE =
            "The model catalog is temporarily unavailable.";

    private ApiKeyModelDiscoveryException(Throwable cause) {
        super(UNAVAILABLE_MESSAGE, cause);
    }

    public static ApiKeyModelDiscoveryException unavailable(Throwable cause) {
        return new ApiKeyModelDiscoveryException(cause);
    }
}
