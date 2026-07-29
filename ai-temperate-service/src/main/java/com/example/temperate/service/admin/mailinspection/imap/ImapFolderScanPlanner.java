package com.example.temperate.service.admin.mailinspection.imap;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 发现可读 IMAP 文件夹并固定优先扫描垃圾邮件与收件箱，且不记录文件夹名称。
 */
public final class ImapFolderScanPlanner {

    private static final List<String> JUNK_KEYWORDS =
            List.of("junk", "spam", "bulk", "垃圾", "垃圾邮件", "广告邮件");
    private static final List<String> INBOX_KEYWORDS =
            List.of("inbox", "收件箱");

    private final List<String> configuredOrder;

    public ImapFolderScanPlanner(List<String> configuredOrder) {
        List<String> normalized = configuredOrder == null
                ? List.of()
                : configuredOrder.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        this.configuredOrder = normalized.isEmpty()
                ? List.of("Junk Email", "INBOX")
                : normalized;
    }

    /**
     * 先加入服务端实际发现的垃圾/收件箱，再加入固定回退名，最后覆盖其他可读文件夹。
     */
    public List<String> resolveScanOrder(Store store) throws MessagingException {
        List<String> discovered = discover(store);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        addMatches(discovered, JUNK_KEYWORDS, ordered);
        addMatches(discovered, INBOX_KEYWORDS, ordered);
        for (String configured : configuredOrder) {
            String resolved = resolveExisting(discovered, configured);
            ordered.add(resolved == null ? configured : resolved);
        }
        ordered.addAll(discovered);
        return List.copyOf(ordered);
    }

    private static List<String> discover(Store store) throws MessagingException {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Folder root = store.getDefaultFolder();
        if (root != null) {
            collect(root, names, 0);
        }
        return new ArrayList<>(names);
    }

    private static void collect(Folder folder, Set<String> target, int depth)
            throws MessagingException {
        if (folder == null || depth > 8) {
            return;
        }
        String fullName = trimToNull(folder.getFullName());
        if (fullName != null
                && !"/".equals(fullName)
                && !"[Gmail]".equalsIgnoreCase(fullName)
                && (folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
            target.add(fullName);
        }
        Folder[] children = folder.list();
        if (children != null) {
            for (Folder child : children) {
                collect(child, target, depth + 1);
            }
        }
    }

    private static void addMatches(
            List<String> discovered,
            List<String> keywords,
            Set<String> target) {
        for (String name : discovered) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (keywords.stream().anyMatch(normalized::contains)) {
                target.add(name);
            }
        }
    }

    private static String resolveExisting(
            List<String> discovered,
            String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return discovered.stream()
                .filter(name -> name.equalsIgnoreCase(configured.trim()))
                .findFirst()
                .orElse(null);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
