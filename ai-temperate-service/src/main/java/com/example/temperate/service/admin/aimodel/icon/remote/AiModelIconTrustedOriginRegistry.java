package com.example.temperate.service.admin.aimodel.icon.remote;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidationContext;
import com.example.temperate.service.admin.aimodel.icon.remote.config.AiModelIconRemoteSvgProperties;
import com.example.temperate.service.admin.aimodel.icon.remote.config.AiModelIconVendor;
import java.net.IDN;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 根据远程图片最终响应主机选择严格或可信官方 SVG 验证上下文。
 *
 * <p>Registry 只读取服务端配置并执行点边界后缀匹配；它不解析 DNS、不信任 URL 路径，
 * 也不会把共享 CDN 的其他租户继承为可信来源。配置重复或跨厂商重叠时应用启动失败。</p>
 */
@Component
public final class AiModelIconTrustedOriginRegistry {

    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*"
                    + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern IPV4_LITERAL =
            Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    private final boolean enabled;
    private final Map<AiModelIconVendor, Set<String>> hostsByVendor;
    private final List<TrustedHost> orderedHosts;

    public AiModelIconTrustedOriginRegistry(
            AiModelIconRemoteSvgProperties properties) {
        Objects.requireNonNull(properties, "properties");
        this.enabled = properties.trustedOfficialProfileEnabled();

        EnumMap<AiModelIconVendor, Set<String>> normalized =
                new EnumMap<>(AiModelIconVendor.class);
        AiModelIconRemoteSvgProperties.TrustedHosts hosts =
                Objects.requireNonNull(properties.trustedHosts(), "trustedHosts");
        normalized.put(AiModelIconVendor.OPENAI, normalize(hosts.openai()));
        normalized.put(AiModelIconVendor.ANTHROPIC, normalize(hosts.anthropic()));
        normalized.put(AiModelIconVendor.GOOGLE, normalize(hosts.google()));
        normalized.put(AiModelIconVendor.XAI, normalize(hosts.xai()));
        normalized.put(AiModelIconVendor.DEEPSEEK, normalize(hosts.deepseek()));
        normalized.put(AiModelIconVendor.ZHIPU, normalize(hosts.zhipu()));
        normalized.put(AiModelIconVendor.MOONSHOT, normalize(hosts.moonshot()));
        normalized.put(AiModelIconVendor.ALIBABA_QWEN, normalize(hosts.qwen()));
        validateNoCrossVendorOverlap(normalized);
        this.hostsByVendor = Map.copyOf(normalized);

        List<TrustedHost> flattened = new ArrayList<>();
        normalized.forEach((vendor, roots) ->
                roots.forEach(root -> flattened.add(new TrustedHost(vendor, root))));
        flattened.sort((left, right) ->
                Integer.compare(right.rootHost().length(), left.rootHost().length()));
        this.orderedHosts = List.copyOf(flattened);
    }

    /**
     * 为最终响应主机解析验证上下文；未启用或未命中时始终回到严格档位。
     */
    public AiModelIconImageValidationContext resolve(String finalHost) {
        if (!enabled) {
            return AiModelIconImageValidationContext.strict();
        }
        String normalizedHost = normalizeHost(finalHost);
        for (TrustedHost candidate : orderedHosts) {
            if (matches(normalizedHost, candidate.rootHost())) {
                return AiModelIconImageValidationContext.trustedOfficial(
                        candidate.vendor());
            }
        }
        return AiModelIconImageValidationContext.strict();
    }

    Map<AiModelIconVendor, Set<String>> configuredHosts() {
        return hostsByVendor;
    }

    private static Set<String> normalize(List<String> configuredHosts) {
        if (configuredHosts == null || configuredHosts.isEmpty()) {
            throw new IllegalStateException(
                    "Trusted official SVG host list must not be empty.");
        }
        return configuredHosts.stream()
                .map(AiModelIconTrustedOriginRegistry::normalizeHost)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeHost(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Trusted official SVG host must not be blank.");
        }
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        while (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        try {
            candidate = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Trusted official SVG host is invalid.",
                    exception);
        }
        if (candidate.contains("*")
                || candidate.contains("/")
                || candidate.contains(":")
                || IPV4_LITERAL.matcher(candidate).matches()
                || !HOSTNAME.matcher(candidate).matches()) {
            throw new IllegalStateException(
                    "Trusted official SVG host must be a DNS hostname.");
        }
        return candidate;
    }

    private static void validateNoCrossVendorOverlap(
            Map<AiModelIconVendor, Set<String>> configured) {
        List<TrustedHost> entries = new ArrayList<>();
        configured.forEach((vendor, roots) ->
                roots.forEach(root -> entries.add(new TrustedHost(vendor, root))));
        for (int leftIndex = 0; leftIndex < entries.size(); leftIndex++) {
            TrustedHost left = entries.get(leftIndex);
            for (int rightIndex = leftIndex + 1;
                    rightIndex < entries.size();
                    rightIndex++) {
                TrustedHost right = entries.get(rightIndex);
                if (left.vendor() != right.vendor()
                        && (matches(left.rootHost(), right.rootHost())
                        || matches(right.rootHost(), left.rootHost()))) {
                    throw new IllegalStateException(
                            "Trusted official SVG hosts overlap across vendors.");
                }
            }
        }
    }

    private static boolean matches(String host, String rootHost) {
        return host.equals(rootHost) || host.endsWith("." + rootHost);
    }

    private record TrustedHost(
            AiModelIconVendor vendor,
            String rootHost) {
    }
}
