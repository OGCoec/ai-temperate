package com.example.temperate.service.admin.mailinspection.imap;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;

/**
 * 从纯文本、HTML、multipart 与嵌套邮件中提取有界文本，供证据匹配而不保存邮件正文。
 */
public final class MailBodyExtractor {

    private final int maxContentChars;

    public MailBodyExtractor(int maxContentChars) {
        if (maxContentChars < 1) {
            throw new IllegalArgumentException("maxContentChars must be positive");
        }
        this.maxContentChars = maxContentChars;
    }

    public String extract(Part part) throws MessagingException, IOException {
        StringBuilder builder = new StringBuilder();
        append(part, builder);
        return normalize(builder.toString());
    }

    private void append(Part part, StringBuilder builder)
            throws MessagingException, IOException {
        if (part == null || builder.length() >= maxContentChars) {
            return;
        }
        if (part.isMimeType("text/plain")) {
            appendStringContent(part, builder, false);
            return;
        }
        if (part.isMimeType("text/html")) {
            appendStringContent(part, builder, true);
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (content instanceof MimeMultipart multipart) {
                for (int index = 0;
                        index < multipart.getCount()
                                && builder.length() < maxContentChars;
                        index++) {
                    BodyPart child = multipart.getBodyPart(index);
                    append(child, builder);
                }
            }
            return;
        }
        if (part.isMimeType("message/rfc822")) {
            Object content = part.getContent();
            if (content instanceof Part nested) {
                append(nested, builder);
            }
        }
    }

    private void appendStringContent(
            Part part,
            StringBuilder builder,
            boolean html) throws MessagingException, IOException {
        Object content = part.getContent();
        if (!(content instanceof String text) || text.isBlank()) {
            return;
        }
        String boundedSource = text.substring(
                0, Math.min(text.length(), maxContentChars));
        String safeText = html
                ? boundedSource.replaceAll("(?s)<[^>]+>", " ")
                : boundedSource;
        int remaining = maxContentChars - builder.length();
        if (remaining <= 0) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
            remaining--;
        }
        if (remaining > 0) {
            builder.append(safeText, 0, Math.min(safeText.length(), remaining));
        }
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("&#x2F;", "/")
                .replace("&#47;", "/")
                .replace("&#x3D;", "=")
                .replace("&#61;", "=")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}
