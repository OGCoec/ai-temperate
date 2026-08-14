package com.example.temperate.web.user.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * 该契约测试是来确保 API Key 管理 Controller 能被方法校验切面代理，防止 final 类型导致应用上下文启动失败。
 */
final class CurrentUserApiKeyControllerContractTest {

    @Test
    void remainsProxyableForMethodValidation() {
        assertThat(Modifier.isFinal(CurrentUserApiKeyController.class.getModifiers()))
                .isFalse();
    }
}
