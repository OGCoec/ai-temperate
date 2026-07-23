package com.example.temperate.service.registration.component.token.impl;

import cn.hutool.core.lang.id.NanoId;
import com.example.temperate.service.registration.component.token.RegistrationTokenGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * 使用密码学安全随机源生成注册流程短生命周期凭据的实现。
 *
 * <p>CSRF、挑战和完成领取标识使用 256 位随机值并编码为 Base64URL；生成器不保存原始值，持久化层必须使用受保护标识。</p>
 */
@Component
public final class SecureRegistrationTokenGenerator implements RegistrationTokenGenerator {

    private static final int RANDOM_BYTES = 32;
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom secureRandom;

    public SecureRegistrationTokenGenerator() {
        this(new SecureRandom());
    }

    SecureRegistrationTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public String newRegisterToken() {
        return NanoId.randomNanoId(38);
    }

    @Override
    public String newFlowCsrf() {
        return randomBase64Url();
    }

    @Override
    public String newChallengeHandle() {
        return randomBase64Url();
    }

    @Override
    public String newCompletionClaim() {
        return randomBase64Url();
    }

    private String randomBase64Url() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }
}
