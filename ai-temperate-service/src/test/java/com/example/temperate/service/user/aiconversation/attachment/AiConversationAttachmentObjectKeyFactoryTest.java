package com.example.temperate.service.user.aiconversation.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证会话附件路径只由规范公共 ID 和服务端附件标识组成，并安全处理不可信文件名。
 */
final class AiConversationAttachmentObjectKeyFactoryTest {

    private final AiConversationAttachmentObjectKeyFactory factory =
            new AiConversationAttachmentObjectKeyFactory();

    @Test
    void buildsTemporaryAndFinalKeysWithoutUsingUntrustedFileNameSegments() {
        String attachmentId = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL";

        assertThat(factory.temporaryKey(
                "AAAAAAAAAAE",
                "AAAAAAAAAAAAAAAAAAAAAQ",
                attachmentId,
                "../../report.PDF"))
                .isEqualTo("ai-temperate/conversations/temp/AAAAAAAAAAE/"
                        + "AAAAAAAAAAAAAAAAAAAAAQ/" + attachmentId + ".pdf");
        assertThat(factory.finalKey(
                "AAAAAAAAAAE",
                "AAAAAAAAAAAAAAAAAAAAAQ",
                "AAAAAAAAAAI",
                attachmentId,
                "archive.tar.gz"))
                .isEqualTo("ai-temperate/conversations/AAAAAAAAAAE/"
                        + "AAAAAAAAAAAAAAAAAAAAAQ/AAAAAAAAAAI/"
                        + attachmentId + ".gz");
    }

    @Test
    void rejectsNonCanonicalIdentifiersAndUnsafeTemporaryLocators() {
        assertThatThrownBy(() -> factory.finalKey(
                "1",
                "AAAAAAAAAAAAAAAAAAAAAQ",
                "AAAAAAAAAAI",
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL",
                "x.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.objectKeyFromTemporaryLocator(
                "ait-temp:///ai-temperate/conversations/../secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
