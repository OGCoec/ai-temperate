package com.example.temperate.service.admin.aimodel.icon.remote.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 绑定模型图标远程 SVG 可信官方兼容档位及八家厂商的域名边界。
 *
 * <p>配置只接受明确主机名；通配符、协议、端口和 IP 字面量会在 Registry 启动时被拒绝，
 * 避免运维配置意外扩大到共享 CDN 或任意网络地址。</p>
 */
@Validated
@ConfigurationProperties(prefix = "ai-model-icon.remote-svg")
public record AiModelIconRemoteSvgProperties(
        boolean trustedOfficialProfileEnabled,
        @NotNull @Valid TrustedHosts trustedHosts) {

    /**
     * 保存每家厂商允许进入可信官方 SVG 档位的根主机名。
     *
     * <p>配置的根主机同时覆盖其真实子域名，但共享 CDN 应配置到足够精确的主机，
     * 例如只配置 {@code www.gstatic.com}，不得配置整个 {@code gstatic.com}。</p>
     */
    public record TrustedHosts(
            @NotEmpty List<@NotBlank String> openai,
            @NotEmpty List<@NotBlank String> anthropic,
            @NotEmpty List<@NotBlank String> google,
            @NotEmpty List<@NotBlank String> xai,
            @NotEmpty List<@NotBlank String> deepseek,
            @NotEmpty List<@NotBlank String> zhipu,
            @NotEmpty List<@NotBlank String> moonshot,
            @NotEmpty List<@NotBlank String> qwen) {
    }
}
