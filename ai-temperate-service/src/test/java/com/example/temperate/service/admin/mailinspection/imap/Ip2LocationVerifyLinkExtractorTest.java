package com.example.temperate.service.admin.mailinspection.imap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证 IP2Location 验证链接的 HTML 实体规范化、可信域名、Token 和畸形链接分类。
 */
final class Ip2LocationVerifyLinkExtractorTest {

    private final Ip2LocationVerifyLinkExtractor extractor =
            new Ip2LocationVerifyLinkExtractor();

    @Test
    void extractsCanonicalUrlAndTokenFromHtmlEntity() {
        Ip2LocationVerifyLinkExtractor.Extraction extraction = extractor.extract(
                "Click https://www.ip2location.io/verify?code&#61;Abc_123-xyz now");

        assertThat(extraction.verifyUrl())
                .isEqualTo("https://www.ip2location.io/verify?code=Abc_123-xyz");
        assertThat(extraction.verifyToken()).isEqualTo("Abc_123-xyz");
        assertThat(extraction.malformed()).isFalse();
    }

    @Test
    void rejectsLookalikeDomainAndMarksMalformedTrustedLink() {
        assertThat(extractor.extract(
                        "https://www.ip2location.io.evil.test/verify?code=Abc_123"))
                .isEqualTo(Ip2LocationVerifyLinkExtractor.Extraction.notFound());

        Ip2LocationVerifyLinkExtractor.Extraction malformed = extractor.extract(
                "https://www.ip2location.io/verify?code=bad%2Ftoken");
        assertThat(malformed.verifyUrl()).isNull();
        assertThat(malformed.malformed()).isTrue();
    }
}
