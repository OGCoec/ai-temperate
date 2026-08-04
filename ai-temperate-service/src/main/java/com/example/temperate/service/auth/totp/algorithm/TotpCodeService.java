package com.example.temperate.service.auth.totp.algorithm;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * 定义 TOTP 随机密钥、Base32、验证码匹配和标准配置 URI 的算法边界。
 *
 * <p>调用方负责加密持久化返回的密钥字节；该接口不访问数据库、Redis 或外部认证器。</p>
 */
public interface TotpCodeService {

    byte[] newSecret();

    String encodeBase32(byte[] secret);

    OptionalLong findMatchingTimeStep(byte[] secret, String code, Instant now);

    String provisioningUri(String accountLabel, byte[] secret);
}
