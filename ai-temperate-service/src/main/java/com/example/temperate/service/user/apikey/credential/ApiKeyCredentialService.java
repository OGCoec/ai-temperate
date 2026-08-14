package com.example.temperate.service.user.apikey.credential;

/**
 * 该服务是来生成 64 字节随机 API Key、校验固定格式并计算完整凭证 HMAC，且不提供解密、找回或版本选择能力。
 */
public interface ApiKeyCredentialService {

    GeneratedApiKey generate();

    byte[] digest(String plaintextApiKey);

    String digestIdentifier(byte[] digest);

    String mask(String keyHint);
}
