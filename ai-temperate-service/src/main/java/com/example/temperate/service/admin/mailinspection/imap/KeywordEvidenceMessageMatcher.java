package com.example.temperate.service.admin.mailinspection.imap;

import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 使用发送方、主题和限制短语匹配 OpenAI 或 Kiro 邮件，不持有账号级数据。
 */
public final class KeywordEvidenceMessageMatcher
        implements MailboxMessageMatcher {

    private final List<String> senderKeywords;
    private final List<String> subjectKeywords;
    private final List<String> restrictedPhrases;

    public KeywordEvidenceMessageMatcher(
            List<String> senderKeywords,
            List<String> subjectKeywords,
            List<String> restrictedPhrases) {
        this.senderKeywords = normalize(senderKeywords);
        this.subjectKeywords = normalize(subjectKeywords);
        this.restrictedPhrases = normalize(restrictedPhrases);
    }

    @Override
    public SearchTerm candidateSearchTerm() {
        List<SearchTerm> terms = new ArrayList<>();
        senderKeywords.forEach(value -> terms.add(new FromStringTerm(value)));
        subjectKeywords.forEach(value -> terms.add(new SubjectTerm(value)));
        if (terms.isEmpty()) {
            return null;
        }
        SearchTerm combined = terms.getFirst();
        for (int index = 1; index < terms.size(); index++) {
            combined = new OrTerm(combined, terms.get(index));
        }
        return combined;
    }

    @Override
    public boolean isCandidate(String sender, String subject) {
        return containsAny(sender, senderKeywords)
                || containsAny(subject, subjectKeywords);
    }

    @Override
    public boolean requiresBody() {
        return true;
    }

    @Override
    public MailboxMessageMatch inspect(String subject, String body) {
        String content = normalizeValue(
                (subject == null ? "" : subject)
                        + "\n"
                        + (body == null ? "" : body));
        for (String phrase : restrictedPhrases) {
            if (content.contains(phrase)) {
                return MailboxMessageMatch.restricted(phrase);
            }
        }
        // 普通候选不能提前结束；后续更新邮件可能包含更高优先级的限制证据。
        return MailboxMessageMatch.candidateOnly(false);
    }

    private static boolean containsAny(String raw, List<String> values) {
        String normalized = normalizeValue(raw);
        return values.stream().anyMatch(normalized::contains);
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(KeywordEvidenceMessageMatcher::normalizeValue)
                .distinct()
                .toList();
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
