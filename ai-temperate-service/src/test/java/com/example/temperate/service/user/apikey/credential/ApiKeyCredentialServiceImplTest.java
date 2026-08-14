package com.example.temperate.service.user.apikey.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apikey.credential.impl.ApiKeyCredentialServiceImpl;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 该测试是来约束 64 字节 API Key 生成、完整 89 字符 HMAC 范围、脱敏和单 Secret 启动校验，不允许引入密钥版本或可逆加密。
 */
final class ApiKeyCredentialServiceImplTest {

    @Test
    void productionConstructorIsExplicitlySelectedForSpringInjection()
            throws NoSuchMethodException {
        boolean autowired = ApiKeyCredentialServiceImpl.class
                .getConstructor(ApiKeyProperties.class)
                .isAnnotationPresent(Autowired.class);

        assertThat(autowired).isTrue();
    }

    @Test
    void generatedCredentialUsesExactOpenAiCompatibleShape() {
        ApiKeyCredentialService service = serviceWithSecret("0123456789abcdef0123456789abcdef");

        GeneratedApiKey generated = service.generate();

        assertThat(generated.plaintext()).matches("^sk-[A-Za-z0-9_-]{86}$");
        assertThat(generated.plaintext()).hasSize(89);
        assertThat(generated.digest()).hasSize(32);
        assertThat(generated.hint()).isEqualTo(generated.plaintext().substring(85));
        assertThat(generated.maskedKey()).isEqualTo("sk-…" + generated.hint());
        assertThat(service.digest(generated.plaintext())).isEqualTo(generated.digest());
    }

    @Test
    void hmacCoversPrefixAndPayloadAsOneCredential() throws GeneralSecurityException {
        String secret = "0123456789abcdef0123456789abcdef";
        ApiKeyCredentialService service = serviceWithSecret(secret);
        String key = "sk-" + "A".repeat(86);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] fullCredentialDigest = mac.doFinal(key.getBytes(StandardCharsets.US_ASCII));
        mac.reset();
        byte[] payloadOnlyDigest = mac.doFinal(key.substring(3).getBytes(StandardCharsets.US_ASCII));

        assertThat(service.digest(key))
                .isEqualTo(fullCredentialDigest)
                .isNotEqualTo(payloadOnlyDigest);
    }

    @Test
    void malformedKeysAreRejectedBeforeHmacLookup() {
        ApiKeyCredentialService service = serviceWithSecret("0123456789abcdef0123456789abcdef");

        assertThatThrownBy(() -> service.digest("sk-***"))
                .isInstanceOf(InvalidApiKeyFormatException.class);
        assertThatThrownBy(() -> service.digest("sk-" + "A".repeat(85) + "="))
                .isInstanceOf(InvalidApiKeyFormatException.class);
    }

    @Test
    void shortOrInvalidSecretFailsConstruction() {
        ApiKeyProperties invalidBase64 = properties("not-base64");
        ApiKeyProperties shortSecret = properties(Base64.getEncoder().encodeToString(
                "too-short".getBytes(StandardCharsets.UTF_8)));
        ApiKeyProperties missingWhileDisabled = new ApiKeyProperties();
        missingWhileDisabled.setEnabled(false);

        assertThatThrownBy(() -> new ApiKeyCredentialServiceImpl(invalidBase64))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeyCredentialServiceImpl(shortSecret))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeyCredentialServiceImpl(missingWhileDisabled))
                .isInstanceOf(IllegalStateException.class);
        assertThat(missingWhileDisabled.isHmacSecretValid()).isFalse();
    }

    private static ApiKeyCredentialService serviceWithSecret(String secret) {
        return new ApiKeyCredentialServiceImpl(properties(
                Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8))));
    }

    private static ApiKeyProperties properties(String secretBase64) {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setEnabled(true);
        properties.setHmacSecretBase64(secretBase64);
        return properties;
    }
}
