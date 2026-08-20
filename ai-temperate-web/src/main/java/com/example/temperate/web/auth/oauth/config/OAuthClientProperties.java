package com.example.temperate.web.auth.oauth.config;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定 GitHub/Google OAuth 客户端、固定回调和 H5/Android 返回地址的启动配置。
 *
 * <p>客户端不得提供 returnUrl；全部 URI 必须是无查询参数的 HTTPS 本站地址。功能关闭时允许 Secret 为空，
 * 开启时缺失任一凭据会在启动期失败。</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.oauth")
public record OAuthClientProperties(
        boolean enabled,
        @NotNull URI publicBaseUrl,
        @NotNull URI h5ReturnUrl,
        @NotNull URI androidReturnUrl,
        @Valid @NotNull Google google,
        @Valid @NotNull Github github) {

    public record Google(
            String clientId,
            String clientSecret,
            String androidServerClientId) {
    }

    public record Github(String clientId, String clientSecret) {
    }

    @AssertTrue(message = "Enabled OAuth requires HTTPS fixed URLs and all client credentials")
    public boolean isValidConfiguration() {
        boolean urlsValid = secureBase(publicBaseUrl)
                && secureReturn(h5ReturnUrl)
                && secureReturn(androidReturnUrl)
                && sameHost(publicBaseUrl, h5ReturnUrl)
                && sameHost(publicBaseUrl, androidReturnUrl);
        if (!enabled) {
            return urlsValid;
        }
        return urlsValid
                && present(google.clientId())
                && present(google.clientSecret())
                && present(google.androidServerClientId())
                && present(github.clientId())
                && present(github.clientSecret());
    }

    public URI callbackUri(OAuthProvider provider) {
        return publicBaseUrl.resolve(
                "/api/auth/oauth2/code/" + provider.name().toLowerCase(Locale.ROOT));
    }

    private static boolean secureBase(URI value) {
        return value != null
                && "https".equals(value.getScheme())
                && value.getHost() != null
                && (value.getPath() == null || value.getPath().isEmpty())
                && value.getRawQuery() == null
                && value.getRawFragment() == null;
    }

    private static boolean secureReturn(URI value) {
        return value != null
                && "https".equals(value.getScheme())
                && value.getHost() != null
                && value.getRawQuery() == null
                && value.getRawFragment() == null;
    }

    private static boolean sameHost(URI left, URI right) {
        return left != null && right != null && left.getHost().equals(right.getHost());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && value.length() <= 512;
    }
}
