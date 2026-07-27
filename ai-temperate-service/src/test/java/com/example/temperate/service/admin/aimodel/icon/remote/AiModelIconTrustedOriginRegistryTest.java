package com.example.temperate.service.admin.aimodel.icon.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconSvgPolicyProfile;
import com.example.temperate.service.admin.aimodel.icon.remote.config.AiModelIconRemoteSvgProperties;
import com.example.temperate.service.admin.aimodel.icon.remote.config.AiModelIconVendor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证可信官方 SVG Registry 只按最终主机的点边界选择厂商，并拒绝跨厂商重叠配置。
 */
final class AiModelIconTrustedOriginRegistryTest {

    @Test
    void resolvesExactHostsAndTrueSubdomains() {
        AiModelIconTrustedOriginRegistry registry =
                new AiModelIconTrustedOriginRegistry(properties(true));

        var context = registry.resolve("chatgpt.com");
        assertThat(context.svgPolicyProfile())
                .isEqualTo(AiModelIconSvgPolicyProfile.TRUSTED_OFFICIAL);
        assertThat(context.trustedVendor())
                .isEqualTo(AiModelIconVendor.OPENAI);
        assertThat(registry.resolve("cdn.chatgpt.com").trustedVendor())
                .isEqualTo(AiModelIconVendor.OPENAI);
        assertThat(registry.resolve("www.gstatic.com").trustedVendor())
                .isEqualTo(AiModelIconVendor.GOOGLE);
        assertThat(registry.resolve("CHATGPT.COM.").trustedVendor())
                .isEqualTo(AiModelIconVendor.OPENAI);
        assertThat(registry.resolve("assets.claude.ai").trustedVendor())
                .isEqualTo(AiModelIconVendor.ANTHROPIC);
        assertThat(registry.resolve("gemini.google.com").trustedVendor())
                .isEqualTo(AiModelIconVendor.GOOGLE);
        assertThat(registry.resolve("cdn.grok.com").trustedVendor())
                .isEqualTo(AiModelIconVendor.XAI);
        assertThat(registry.resolve("cdn.deepseek.com").trustedVendor())
                .isEqualTo(AiModelIconVendor.DEEPSEEK);
        assertThat(registry.resolve("assets.bigmodel.cn").trustedVendor())
                .isEqualTo(AiModelIconVendor.ZHIPU);
        assertThat(registry.resolve("static.kimi.com").trustedVendor())
                .isEqualTo(AiModelIconVendor.MOONSHOT);
        assertThat(registry.resolve("qwen.ai").trustedVendor())
                .isEqualTo(AiModelIconVendor.ALIBABA_QWEN);
        assertThat(registry.resolve("assets.qwen.ai").trustedVendor())
                .isEqualTo(AiModelIconVendor.ALIBABA_QWEN);
    }

    @Test
    void doesNotTrustLookalikesOrWholeSharedCdn() {
        AiModelIconTrustedOriginRegistry registry =
                new AiModelIconTrustedOriginRegistry(properties(true));

        assertThat(registry.resolve("chatgpt.com.attacker.test").svgPolicyProfile())
                .isEqualTo(AiModelIconSvgPolicyProfile.STRICT);
        assertThat(registry.resolve("gstatic.com").svgPolicyProfile())
                .isEqualTo(AiModelIconSvgPolicyProfile.STRICT);
        assertThat(registry.resolve("images.ctfassets.net").svgPolicyProfile())
                .isEqualTo(AiModelIconSvgPolicyProfile.STRICT);
        assertThat(registry.resolve("evil-chatgpt.com").svgPolicyProfile())
                .isEqualTo(AiModelIconSvgPolicyProfile.STRICT);
        assertThat(registry.resolve("qwen.ai.attacker.test").svgPolicyProfile())
                .isEqualTo(AiModelIconSvgPolicyProfile.STRICT);
        assertThat(registry.resolve("evil-qwen.ai").svgPolicyProfile())
                .isEqualTo(AiModelIconSvgPolicyProfile.STRICT);
    }

    @Test
    void disabledRegistryAlwaysUsesStrictProfile() {
        AiModelIconTrustedOriginRegistry registry =
                new AiModelIconTrustedOriginRegistry(properties(false));

        assertThat(registry.resolve("chatgpt.com").svgPolicyProfile())
                .isEqualTo(AiModelIconSvgPolicyProfile.STRICT);
    }

    @Test
    void rejectsCrossVendorOverlappingHosts() {
        AiModelIconRemoteSvgProperties.TrustedHosts hosts =
                new AiModelIconRemoteSvgProperties.TrustedHosts(
                        List.of("example.test"),
                        List.of("cdn.example.test"),
                        List.of("www.gstatic.com"),
                        List.of("x.ai"),
                        List.of("deepseek.com"),
                        List.of("zhipuai.cn"),
                        List.of("kimi.com"),
                        List.of("assets.example.test"));

        assertThatThrownBy(() -> new AiModelIconTrustedOriginRegistry(
                new AiModelIconRemoteSvgProperties(true, hosts)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void rejectsWildcardProtocolPortPathAndIpConfiguration() {
        for (String invalid : List.of(
                "*.chatgpt.com",
                "https://chatgpt.com",
                "chatgpt.com:443",
                "chatgpt.com/cdn",
                "198.18.0.1")) {
            AiModelIconRemoteSvgProperties.TrustedHosts hosts =
                    new AiModelIconRemoteSvgProperties.TrustedHosts(
                            List.of(invalid),
                            List.of("claude.ai"),
                            List.of("www.gstatic.com"),
                            List.of("x.ai"),
                            List.of("deepseek.com"),
                            List.of("zhipuai.cn"),
                            List.of("kimi.com"),
                            List.of("qwen.ai"));

            assertThatThrownBy(() -> new AiModelIconTrustedOriginRegistry(
                    new AiModelIconRemoteSvgProperties(true, hosts)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static AiModelIconRemoteSvgProperties properties(boolean enabled) {
        return new AiModelIconRemoteSvgProperties(
                enabled,
                new AiModelIconRemoteSvgProperties.TrustedHosts(
                        List.of("chatgpt.com", "openai.com"),
                        List.of("claude.ai", "anthropic.com"),
                        List.of("gemini.google.com", "www.gstatic.com"),
                        List.of("grok.com", "x.ai"),
                        List.of("deepseek.com"),
                        List.of("zhipuai.cn", "chatglm.cn", "bigmodel.cn"),
                        List.of("kimi.com", "moonshot.cn", "moonshot.ai"),
                        List.of("qwen.ai")));
    }
}
