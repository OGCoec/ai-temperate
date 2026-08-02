package com.example.temperate.service.user.aiconversation.attachment;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 只用规范公共 ID 和服务端生成的随机标识构造会话附件 OSS 路径，阻断目录穿越和跨用户覆盖。
 */
@Component
public final class AiConversationAttachmentObjectKeyFactory {

    private static final Pattern PUBLIC_LONG_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern PUBLIC_HYBRID_ID = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    private static final Pattern ATTACHMENT_ID = Pattern.compile("^[A-Za-z0-9_-]{38}$");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("^[a-z0-9]{1,16}$");
    private static final String ROOT = "ai-temperate/conversations/";
    private static final String TEMPORARY_SCHEME = "ait-temp";

    public String temporaryKey(
            String userPublicId,
            String uploadSessionId,
            String attachmentId,
            String fileName) {
        return ROOT + "temp/" + requirePublicLongId(userPublicId) + "/"
                + requirePublicHybridId(uploadSessionId) + "/"
                + requireAttachmentId(attachmentId) + "." + safeExtension(fileName);
    }

    public String finalKey(
            String userPublicId,
            String conversationPublicId,
            String messagePublicId,
            String attachmentId,
            String fileName) {
        return ROOT + requirePublicLongId(userPublicId) + "/"
                + requirePublicHybridId(conversationPublicId) + "/"
                + requirePublicLongId(messagePublicId) + "/"
                + requireAttachmentId(attachmentId) + "." + safeExtension(fileName);
    }

    public String temporaryLocator(String objectKey) {
        String normalized = requireOwnedObjectKey(objectKey);
        return TEMPORARY_SCHEME + ":///" + normalized;
    }

    public String objectKeyFromTemporaryLocator(String locator) {
        try {
            URI uri = URI.create(locator);
            if (!TEMPORARY_SCHEME.equals(uri.getScheme())
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Temporary attachment locator is invalid");
            }
            String path = uri.getPath();
            return requireOwnedObjectKey(path.startsWith("/") ? path.substring(1) : path);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Temporary attachment locator is invalid", exception);
        }
    }

    public String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "attachment.bin";
        }
        String leaf = fileName.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1);
        StringBuilder safe = new StringBuilder(Math.min(leaf.length(), 255));
        leaf.codePoints().limit(255).forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)
                    && codePoint != ':'
                    && codePoint != '\u0000') {
                safe.appendCodePoint(codePoint);
            }
        });
        String result = safe.toString().trim();
        return result.isEmpty() ? "attachment.bin" : result;
    }

    public String safeExtension(String fileName) {
        String safeName = sanitizeFileName(fileName);
        int dot = safeName.lastIndexOf('.');
        if (dot < 0 || dot == safeName.length() - 1) {
            return "bin";
        }
        String extension = safeName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SAFE_EXTENSION.matcher(extension).matches() ? extension : "bin";
    }

    public String requireAttachmentId(String value) {
        if (value == null || !ATTACHMENT_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("attachmentId must be a 38-character NanoID");
        }
        return value;
    }

    private static String requirePublicLongId(String value) {
        if (value == null || !PUBLIC_LONG_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Public long ID must contain 11 Base64URL characters");
        }
        return value;
    }

    private static String requirePublicHybridId(String value) {
        if (value == null || !PUBLIC_HYBRID_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Public hybrid ID must contain 22 Base64URL characters");
        }
        return value;
    }

    private static String requireOwnedObjectKey(String value) {
        if (value == null
                || !value.startsWith(ROOT)
                || value.startsWith("/")
                || value.contains("\\")
                || value.contains("..")) {
            throw new IllegalArgumentException("Object key is outside the conversation namespace");
        }
        return value;
    }
}
