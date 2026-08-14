package com.example.temperate.service.user.apikey.credential;

/**
 * 该异常是来统一表示公开 Bearer 凭证在 HMAC 和数据库查询前就不满足固定格式，外部必须映射为同一 invalid_api_key 响应。
 */
public final class InvalidApiKeyFormatException extends RuntimeException {

    public InvalidApiKeyFormatException() {
        super("API Key format is invalid");
    }
}
