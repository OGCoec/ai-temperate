package com.example.temperate.web.auth.oauth.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 约束 GitHub 身份接口的等待上限，防止网络抖动时过早终止 OAuth 回调。
 */
class GithubOAuthProviderClientStrategyTimeoutContractTest {

    @Test
    void providerIdentityLookupAllowsThirtySeconds() throws ReflectiveOperationException {
        Field timeout = GithubOAuthProviderClientStrategy.class
                .getDeclaredField("PROVIDER_TIMEOUT");
        timeout.setAccessible(true);

        assertThat(timeout.get(null)).isEqualTo(Duration.ofSeconds(30));
    }
}
