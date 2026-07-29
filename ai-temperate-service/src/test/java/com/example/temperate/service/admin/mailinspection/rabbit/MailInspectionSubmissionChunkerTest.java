package com.example.temperate.service.admin.mailinspection.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证Submission按编码后的UTF-8明文边界分块且不会改变原始lineNumber顺序。
 */
final class MailInspectionSubmissionChunkerTest {

    @Test
    void splitsCredentialsWithoutChangingOrder() {
        MailInspectionSubmissionChunker chunker =
                new MailInspectionSubmissionChunker(
                        AdminMailInspectionProperties.defaults());
        String token = "x".repeat(8_000);
        List<MailboxCredential> credentials =
                java.util.stream.IntStream.rangeClosed(1, 30)
                        .mapToObj(line -> credential(line, token))
                        .toList();

        List<List<MailboxCredential>> chunks = chunker.chunk(credentials);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.stream()
                        .flatMap(List::stream)
                        .map(MailboxCredential::lineNumber))
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 30)
                                .boxed()
                                .toList());
    }

    private static MailboxCredential credential(int line, String token) {
        return new MailboxCredential(
                line,
                "user" + line + "@example.com",
                "00000000-0000-0000-0000-000000000000",
                token);
    }
}
