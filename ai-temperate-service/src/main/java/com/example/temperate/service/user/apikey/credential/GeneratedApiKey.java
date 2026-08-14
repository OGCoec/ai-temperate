package com.example.temperate.service.user.apikey.credential;

/**
 * 该值对象是来在创建事务内短暂传递一次性 API Key 原文、不可逆摘要和脱敏提示，原文不得进入持久化或日志。
 */
public record GeneratedApiKey(
        String plaintext,
        byte[] digest,
        String hint,
        String maskedKey) {

    public GeneratedApiKey {
        digest = digest.clone();
    }

    @Override
    public byte[] digest() {
        return digest.clone();
    }
}
