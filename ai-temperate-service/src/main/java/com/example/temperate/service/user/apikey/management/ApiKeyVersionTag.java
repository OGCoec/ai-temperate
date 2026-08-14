package com.example.temperate.service.user.apikey.management;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 该工具是来编解码 API Key 乐观锁的规范强 ETag，只接受单个非负版本并拒绝弱标签、通配符和多值。
 */
public final class ApiKeyVersionTag {

    private static final Pattern STRONG_VERSION = Pattern.compile("^\"v(0|[1-9][0-9]*)\"$");

    private ApiKeyVersionTag() {
    }

    public static String format(long rowVersion) {
        if (rowVersion < 0) {
            throw new IllegalArgumentException("API Key row version must not be negative");
        }
        return "\"v" + rowVersion + "\"";
    }

    public static long parseRequired(String value) {
        if (value == null) {
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.VERSION_REQUIRED,
                    "API Key If-Match is required");
        }
        Matcher matcher = STRONG_VERSION.matcher(value);
        if (!matcher.matches()) {
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.VERSION_INVALID,
                    "API Key If-Match is invalid");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new ApiKeyManagementException(
                    ApiKeyManagementErrorCode.VERSION_INVALID,
                    "API Key If-Match is invalid");
        }
    }
}
