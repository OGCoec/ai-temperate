package com.example.temperate.service.admin.aimodel.icon.storage;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 根据图标显示名称和真实图片格式生成稳定、无随机标识的 OSS Object Key。
 *
 * <p>名称先进行 Unicode 兼容分解，再仅保留 ASCII 字母和数字；其他连续字符折叠为单个连字符。
 * 无法得到安全 Slug 时拒绝上传，禁止回退到原始文件名。</p>
 */
@Component
public final class AiModelIconObjectKeyFactory {

    private final String prefix;

    public AiModelIconObjectKeyFactory(
            @Value("${ai-model-icon.oss.prefix:ai-temperate/models/icons/}") String prefix) {
        this.prefix = normalizePrefix(prefix);
    }

    public String create(String iconName, AiModelIconImageFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        String slug = slug(iconName);
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("iconName cannot produce a safe ASCII slug");
        }
        return prefix + slug + "." + format.extension();
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean separator = false;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')) {
                if (separator && !result.isEmpty()) {
                    result.append('-');
                }
                result.append(character);
                separator = false;
            } else if (!result.isEmpty()) {
                separator = true;
            }
        }
        return result.toString();
    }

    private static String normalizePrefix(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AI model icon OSS prefix must not be blank");
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("AI model icon OSS prefix must be normalized");
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }
}
