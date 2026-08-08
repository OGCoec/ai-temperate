package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationPlanType;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchCommand;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchResult;
import com.example.temperate.service.risk.ip2location.service.Ip2LocationApiKeyService;
import com.example.temperate.web.auth.api.GlobalExceptionHandler;
import com.example.temperate.web.auth.api.WebInvalidInputException;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 验证管理员 IP2Location Key 导入的套餐白名单、文件边界和脱敏响应约束。
 */
class AdminIp2LocationKeyControllerTest {

    @Test
    void publicRequestAndServiceCommandDoNotExposeExpirationInput() {
        assertThat(Arrays.stream(
                                AdminIp2LocationKeyController.BatchRequest.class
                                        .getRecordComponents())
                        .map(component -> component.getName()))
                .doesNotContain("expiresAt", "ttl", "validityDays");
        assertThat(Arrays.stream(Ip2LocationKeyBatchCommand.class.getRecordComponents())
                        .map(component -> component.getName()))
                .doesNotContain("expiresAt", "ttl", "validityDays");
    }

    @Test
    void validTextImportDelegatesOneAtomicBatchWithoutEchoingKeys()
            throws Exception {
        Ip2LocationApiKeyService service = mock(Ip2LocationApiKeyService.class);
        when(service.importBatch(any())).thenReturn(
                new Ip2LocationKeyBatchResult(2, 2, 0, 0));
        AdminIp2LocationKeyController controller =
                new AdminIp2LocationKeyController(service);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "keys.txt",
                "text/plain",
                "first-api-key\n\n second-api-key \n"
                        .getBytes(StandardCharsets.UTF_8));

        Ip2LocationKeyBatchResult result = controller.importText(
                file,
                "FREE",
                50_000L,
                Ip2LocationImportMode.CREATE_ONLY,
                response);

        ArgumentCaptor<Ip2LocationKeyBatchCommand> command =
                ArgumentCaptor.forClass(Ip2LocationKeyBatchCommand.class);
        verify(service).importBatch(command.capture());
        assertThat(command.getValue().planType()).isEqualTo(Ip2LocationPlanType.FREE);
        assertThat(command.getValue().apiKeys())
                .containsExactly("first-api-key", "second-api-key");
        assertThat(result).isEqualTo(new Ip2LocationKeyBatchResult(2, 2, 0, 0));
        assertThat(result.toString())
                .doesNotContain("first-api-key", "second-api-key");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void fileLargerThanTwoHundredFiftySixKilobytesIsRejected() {
        AdminIp2LocationKeyController controller =
                new AdminIp2LocationKeyController(mock(Ip2LocationApiKeyService.class));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "keys.txt",
                "text/plain",
                new byte[256 * 1024 + 1]);

        assertThatThrownBy(() -> controller.importText(
                        file,
                        "FREE",
                        50_000L,
                        Ip2LocationImportMode.CREATE_ONLY,
                        new MockHttpServletResponse()))
                .isInstanceOf(WebInvalidInputException.class);
    }

    @Test
    void moreThanFiveHundredNonEmptyLinesAreRejected() {
        AdminIp2LocationKeyController controller =
                new AdminIp2LocationKeyController(mock(Ip2LocationApiKeyService.class));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "keys.txt",
                "text/plain",
                "abcdefgh\n".repeat(501).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.importText(
                        file,
                        "FREE",
                        50_000L,
                        Ip2LocationImportMode.CREATE_ONLY,
                        new MockHttpServletResponse()))
                .isInstanceOf(WebInvalidInputException.class);
    }

    @Test
    void malformedUtf8IsRejected() {
        AdminIp2LocationKeyController controller =
                new AdminIp2LocationKeyController(mock(Ip2LocationApiKeyService.class));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "keys.txt",
                "text/plain",
                new byte[] {(byte) 0xC3, 0x28});

        assertThatThrownBy(() -> controller.importText(
                        file,
                        "FREE",
                        50_000L,
                        Ip2LocationImportMode.CREATE_ONLY,
                        new MockHttpServletResponse()))
                .isInstanceOf(WebInvalidInputException.class);
    }

    @Test
    void unrelatedMultipartPlanStringIsRejectedBeforeServiceInvocation() {
        Ip2LocationApiKeyService service = mock(Ip2LocationApiKeyService.class);
        AdminIp2LocationKeyController controller =
                new AdminIp2LocationKeyController(service);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "keys.txt",
                "text/plain",
                "contract-test-key\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.importText(
                        file,
                        "RANDOM_PLAN",
                        50_000L,
                        Ip2LocationImportMode.CREATE_ONLY,
                        new MockHttpServletResponse()))
                .isInstanceOf(WebInvalidInputException.class);
        verifyNoInteractions(service);
    }

    @Test
    void unrelatedPlanStringIsRejectedBeforeServiceInvocation() throws Exception {
        Ip2LocationApiKeyService service = mock(Ip2LocationApiKeyService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminIp2LocationKeyController(service))
                .setControllerAdvice(new GlobalExceptionHandler(
                        Clock.fixed(
                                Instant.parse("2026-07-26T00:00:00Z"),
                                ZoneOffset.UTC),
                        mock(AuthCookieWriter.class),
                        mock(AuthFlowCookieWriter.class),
                        mock(com.example.temperate.web.risk.PreAuthTransport.class)))
                .build();

        mockMvc.perform(post("/api/admin/risk/ip2location/keys/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "planType": "RANDOM_PLAN",
                                  "initialQuota": 50000,
                                  "mode": "CREATE_ONLY",
                                  "apiKeys": ["contract-test-key"]
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
