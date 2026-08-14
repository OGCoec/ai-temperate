package com.example.temperate.service.user.apikey.bloom;

/**
 * 该异常是来阻止无法登记 positive mutation 时创建或恢复有效 Key，避免数据库提交后固定 v1 Bloom 产生假阴性。
 */
public final class ApiKeyBloomUnavailableException extends RuntimeException {

    public ApiKeyBloomUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public ApiKeyBloomUnavailableException(String message) {
        super(message);
    }
}
