package com.example.temperate.common.validation.email;

import java.util.Locale;

/**
 * 将外部邮箱文本校验并规范化为可用于身份查询的统一表示。
 *
 * <p>规范化在身份边界完成：只去除首尾 ASCII 空格并按 Locale.ROOT 小写化，随后验证本地段和域名，
 * 防止同一邮箱以多种文本形式进入数据库索引或缓存键。</p>
 */
public final class EmailAddressNormalizer {

    private static final int MAXIMUM_EMAIL_LENGTH = 254;
    private static final int MAXIMUM_DOMAIN_LABEL_LENGTH = 63;
    private static final String LOCAL_SPECIAL_CHARACTERS = "!#$%&'*+-/=?^_`{|}~.";

    private EmailAddressNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            throw invalid();
        }
        // 使用 Locale.ROOT 防止部署机器的区域设置改变身份标识的规范化结果。
        String normalized = trimAsciiSpaces(value).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()
                || normalized.length() > MAXIMUM_EMAIL_LENGTH
                || containsWhitespaceOrControl(normalized)) {
            throw invalid();
        }

        int at = normalized.indexOf('@');
        if (at <= 0 || at != normalized.lastIndexOf('@') || at == normalized.length() - 1) {
            throw invalid();
        }
        String localPart = normalized.substring(0, at);
        String domain = normalized.substring(at + 1);
        if (!isValidLocalPart(localPart) || !isValidDomain(domain)) {
            throw invalid();
        }
        return normalized;
    }

    private static String trimAsciiSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean containsWhitespaceOrControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidLocalPart(String value) {
        if (value.isEmpty()
                || value.startsWith(".")
                || value.endsWith(".")
                || value.contains("..")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isAsciiLetterOrDigit(character)
                    && LOCAL_SPECIAL_CHARACTERS.indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidDomain(String value) {
        String[] labels = value.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty()
                    || label.length() > MAXIMUM_DOMAIN_LABEL_LENGTH
                    || !isAsciiLetterOrDigit(label.charAt(0))
                    || !isAsciiLetterOrDigit(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int index = 0; index < label.length(); index++) {
                char character = label.charAt(index);
                if (!isAsciiLetterOrDigit(character) && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9';
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Email address is invalid.");
    }
}
