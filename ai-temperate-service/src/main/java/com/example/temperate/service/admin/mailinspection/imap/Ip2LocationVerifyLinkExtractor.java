package com.example.temperate.service.admin.mailinspection.imap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从受限文本中提取 IP2Location 官方验证 URL 与 Token，并区分未找到和畸形链接。
 */
public final class Ip2LocationVerifyLinkExtractor {

    private static final Pattern VERIFY_URL = Pattern.compile(
            "https://www\\.ip2location\\.io/verify\\?code=([A-Za-z0-9_-]{6,512})"
                    + "(?![A-Za-z0-9_%/-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRUSTED_VERIFY_PREFIX = Pattern.compile(
            "https://www\\.ip2location\\.io/verify\\?code",
            Pattern.CASE_INSENSITIVE);

    public Extraction extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return Extraction.notFound();
        }
        String normalized = normalize(raw);
        Matcher matcher = VERIFY_URL.matcher(normalized);
        if (matcher.find()) {
            String token = matcher.group(1);
            return new Extraction(
                    "https://www.ip2location.io/verify?code=" + token,
                    token,
                    false);
        }
        return TRUSTED_VERIFY_PREFIX.matcher(normalized).find()
                ? new Extraction(null, null, true)
                : Extraction.notFound();
    }

    private static String normalize(String raw) {
        return raw.replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("&#x2F;", "/")
                .replace("&#47;", "/")
                .replace("&#x3D;", "=")
                .replace("&#61;", "=")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    /**
     * 返回规范 URL、独立 Token 或畸形标记，三者不会包含原始邮件正文。
     */
    public record Extraction(
            String verifyUrl,
            String verifyToken,
            boolean malformed) {

        public static Extraction notFound() {
            return new Extraction(null, null, false);
        }

        @Override
        public String toString() {
            return "Extraction[malformed="
                    + malformed
                    + ",verifyData=protected]";
        }
    }
}
