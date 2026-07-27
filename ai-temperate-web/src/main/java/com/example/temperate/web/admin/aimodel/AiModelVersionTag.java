package com.example.temperate.web.admin.aimodel;

import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编解码 AI 模型乐观锁使用的规范强 ETag。
 *
 * <p>只接受单个 {@code "v<正整数>"}，拒绝弱标签、通配符和多值，避免模糊并发覆盖语义。</p>
 */
public final class AiModelVersionTag {

    private static final Pattern STRONG_VERSION = Pattern.compile("^\"v([1-9][0-9]*)\"$");

    private AiModelVersionTag() {
    }

    public static String format(long rowVersion) {
        if (rowVersion < 1) {
            throw new IllegalArgumentException("AI model row version must be positive.");
        }
        return "\"v" + rowVersion + "\"";
    }

    public static long parseRequired(String value) {
        if (value == null) {
            throw versionRequired();
        }
        Matcher matcher = STRONG_VERSION.matcher(value);
        if (!matcher.matches()) {
            throw versionRequired();
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw versionRequired();
        }
    }

    private static AdminAiModelException versionRequired() {
        return new AdminAiModelException(
                AdminAiModelErrorCode.AI_MODEL_VERSION_REQUIRED,
                "A canonical AI model If-Match version is required.");
    }
}
