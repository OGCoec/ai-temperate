package com.example.temperate.service.admin.mailinspection.imap;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * 验证 MIME 纯文本、HTML、multipart 与正文字符上限的安全提取行为。
 */
final class MailBodyExtractorTest {

    @Test
    void extractsMultipartTextAndStripsHtmlMarkup() throws Exception {
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("plain evidence");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<strong>restricted account</strong>", "text/html; charset=UTF-8");
        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(plain);
        multipart.addBodyPart(html);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setContent(multipart);
        message.saveChanges();

        String extracted = new MailBodyExtractor(200_000).extract(message);

        assertThat(extracted).contains("plain evidence", "restricted account");
        assertThat(extracted).doesNotContain("<strong>");
    }

    @Test
    void truncatesOversizedBody() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setText("x".repeat(500));
        message.saveChanges();

        assertThat(new MailBodyExtractor(100).extract(message)).hasSize(100);
    }
}
